use crate::{
    checker::UsernameChecker,
    config::AppConfig,
    generator::UsernameGenerator,
    model::{CheckResult, CheckStatus, ProxySnapshot, ScanStats, WorkerSnapshot, WorkerState},
    proxy::ProxyPool,
    scheduler::RateScheduler,
    storage::Storage,
};
use anyhow::{Context, Result};
use std::{
    collections::HashSet,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::sync::{Mutex, Notify, mpsc};

#[derive(Debug, Clone, Copy)]
pub enum EngineCommand {
    Pause,
    Resume,
    Stop,
}

#[derive(Debug, Clone)]
pub enum EngineEvent {
    Notice(String),
    Started,
    Paused(bool),
    Worker(WorkerSnapshot),
    Proxies(Vec<ProxySnapshot>),
    Result(CheckResult),
    Stats(ScanStats),
    Cooldown { seconds: f64, detail: Option<String> },
    Finished(String),
    Failed(String),
}

struct Control {
    paused: AtomicBool,
    stopped: AtomicBool,
    notify: Notify,
}

impl Control {
    fn new() -> Self {
        Self {
            paused: AtomicBool::new(false),
            stopped: AtomicBool::new(false),
            notify: Notify::new(),
        }
    }

    async fn wait_ready(&self) -> bool {
        while self.paused.load(Ordering::Relaxed) && !self.stopped.load(Ordering::Relaxed) {
            self.notify.notified().await;
        }
        !self.stopped.load(Ordering::Relaxed)
    }

    fn pause(&self) {
        self.paused.store(true, Ordering::Relaxed);
    }

    fn resume(&self) {
        self.paused.store(false, Ordering::Relaxed);
        self.notify.notify_waiters();
    }

    fn stop(&self) {
        self.stopped.store(true, Ordering::Relaxed);
        self.paused.store(false, Ordering::Relaxed);
        self.notify.notify_waiters();
    }
}

pub async fn run_scan(
    root: PathBuf,
    config: AppConfig,
    event_tx: mpsc::UnboundedSender<EngineEvent>,
    mut command_rx: mpsc::UnboundedReceiver<EngineCommand>,
) {
    if let Err(error) = run_scan_inner(root, config, event_tx.clone(), &mut command_rx).await {
        let _ = event_tx.send(EngineEvent::Failed(format!("{error:#}")));
    }
}

async fn run_scan_inner(
    root: PathBuf,
    config: AppConfig,
    event_tx: mpsc::UnboundedSender<EngineEvent>,
    command_rx: &mut mpsc::UnboundedReceiver<EngineCommand>,
) -> Result<()> {
    config.validate()?;
    let _ = event_tx.send(EngineEvent::Notice("initializing scan…".into()));

    let mut storage = Storage::open(&root)?;
    let checked = storage.load_checked()?;
    let generator = UsernameGenerator::from_config(&config.scanner, &root)?;
    let timeout = Duration::from_secs(10);

    let proxy_pool = if config.proxies.enabled {
        let pool = Arc::new(ProxyPool::from_file(&root, &config.proxies, timeout)?);
        let _ = event_tx.send(EngineEvent::Notice(format!(
            "proxy pool: {} loaded · preflight…",
            pool.len().await
        )));
        let _ = event_tx.send(EngineEvent::Proxies(pool.snapshots().await));
        pool.preflight().await;
        let snapshots = pool.snapshots().await;
        let ready = snapshots
            .iter()
            .filter(|proxy| proxy.state == crate::model::ProxyState::Ready)
            .count();
        let _ = event_tx.send(EngineEvent::Proxies(snapshots));
        let _ = event_tx.send(EngineEvent::Notice(format!(
            "proxy preflight: {ready} ready · {} failed",
            pool.len().await.saturating_sub(ready)
        )));
        Some(pool)
    } else {
        None
    };

    let route = if let Some(pool) = &proxy_pool {
        match pool.select_route().await {
            Some(route) => {
                let _ = event_tx.send(EngineEvent::Notice(format!(
                    "route locked: {}",
                    route.display
                )));
                Some(route)
            }
            None if config.proxies.fallback_direct => {
                let _ = event_tx.send(EngineEvent::Notice(
                    "no healthy proxy · using direct connection".into(),
                ));
                None
            }
            None => anyhow::bail!("no proxy passed Discord preflight"),
        }
    } else {
        let _ = event_tx.send(EngineEvent::Notice("direct connection · proxy routing OFF".into()));
        None
    };

    let checker = Arc::new(UsernameChecker::new(timeout, proxy_pool.clone(), route)?);
    let scheduler = Arc::new(RateScheduler::new(
        config.scanner.interval,
        config.scanner.jitter,
    ));
    let control = Arc::new(Control::new());
    let warmup_complete = Arc::new(AtomicBool::new(false));
    let warmup_lock = Arc::new(Mutex::new(()));

    let (queue_tx, queue_rx) = mpsc::channel::<String>(config.scanner.queue_size);
    let queue_rx = Arc::new(Mutex::new(queue_rx));
    let (result_tx, mut result_rx) = mpsc::unbounded_channel::<CheckResult>();

    let command_control = control.clone();
    let command_events = event_tx.clone();
    let mut commands = std::mem::replace(command_rx, mpsc::unbounded_channel().1);
    let command_task = tokio::spawn(async move {
        while let Some(command) = commands.recv().await {
            match command {
                EngineCommand::Pause => {
                    command_control.pause();
                    let _ = command_events.send(EngineEvent::Paused(true));
                }
                EngineCommand::Resume => {
                    command_control.resume();
                    let _ = command_events.send(EngineEvent::Paused(false));
                }
                EngineCommand::Stop => {
                    command_control.stop();
                    return;
                }
            }
        }
    });

    let producer_control = control.clone();
    let workers = config.scanner.workers;
    let limit = config.scanner.limit;
    let producer = tokio::spawn(async move {
        produce(generator, checked, queue_tx, producer_control, workers, limit).await;
    });

    let mut worker_tasks = Vec::with_capacity(workers);
    for worker_id in 1..=workers {
        let queue_rx = queue_rx.clone();
        let result_tx = result_tx.clone();
        let checker = checker.clone();
        let scheduler = scheduler.clone();
        let control = control.clone();
        let event_tx = event_tx.clone();
        let warmup_complete = warmup_complete.clone();
        let warmup_lock = warmup_lock.clone();
        worker_tasks.push(tokio::spawn(async move {
            worker_loop(
                worker_id,
                queue_rx,
                result_tx,
                checker,
                scheduler,
                control,
                event_tx,
                warmup_complete,
                warmup_lock,
            )
            .await;
        }));
    }
    drop(result_tx);

    let _ = event_tx.send(EngineEvent::Started);
    let _ = event_tx.send(EngineEvent::Notice(format!(
        "{} · length {} · {} workers · {:.3}s interval",
        config.scanner.mode,
        config.scanner.length,
        config.scanner.workers,
        config.scanner.interval
    )));

    let mut stats = ScanStats::default();
    while let Some(result) = result_rx.recv().await {
        storage.save(&result)?;
        stats.record(result.status);
        let _ = event_tx.send(EngineEvent::Result(result));
        let _ = event_tx.send(EngineEvent::Stats(stats.clone()));
        if let Some(pool) = &proxy_pool {
            let _ = event_tx.send(EngineEvent::Proxies(pool.snapshots().await));
        }
    }

    control.stop();
    let _ = producer.await;
    for worker in worker_tasks {
        let _ = worker.await;
    }
    command_task.abort();

    let reason = if stats.checked == 0 {
        "scan stopped".to_owned()
    } else {
        format!("scan finished · {} checked", stats.checked)
    };
    let _ = event_tx.send(EngineEvent::Finished(reason));
    Ok(())
}

async fn produce(
    generator: UsernameGenerator,
    checked: HashSet<String>,
    queue_tx: mpsc::Sender<String>,
    control: Arc<Control>,
    workers: usize,
    limit: Option<u64>,
) {
    let mut seen = checked;
    let mut generated = 0_u64;
    for username in generator {
        if !control.wait_ready().await {
            break;
        }
        if limit.is_some_and(|limit| generated >= limit) {
            break;
        }
        if !seen.insert(username.clone()) {
            continue;
        }
        if queue_tx.send(username).await.is_err() {
            break;
        }
        generated += 1;
    }
    for _ in 0..workers {
        let _ = queue_tx.send(String::new()).await;
    }
}

#[allow(clippy::too_many_arguments)]
async fn worker_loop(
    worker_id: usize,
    queue_rx: Arc<Mutex<mpsc::Receiver<String>>>,
    result_tx: mpsc::UnboundedSender<CheckResult>,
    checker: Arc<UsernameChecker>,
    scheduler: Arc<RateScheduler>,
    control: Arc<Control>,
    event_tx: mpsc::UnboundedSender<EngineEvent>,
    warmup_complete: Arc<AtomicBool>,
    warmup_lock: Arc<Mutex<()>>,
) {
    worker_event(&event_tx, worker_id, WorkerState::Idle, None, None, None);
    loop {
        if !control.wait_ready().await {
            break;
        }
        let username = {
            let mut receiver = queue_rx.lock().await;
            receiver.recv().await
        };
        let Some(username) = username else { break; };
        if username.is_empty() {
            break;
        }

        worker_event(
            &event_tx,
            worker_id,
            WorkerState::Active,
            Some(username.clone()),
            None,
            Some("checking".into()),
        );

        let mut rate_retries = 0_u8;
        let result = loop {
            if !control.wait_ready().await {
                return worker_event(
                    &event_tx,
                    worker_id,
                    WorkerState::Stopped,
                    None,
                    None,
                    None,
                );
            }

            let result = if !warmup_complete.load(Ordering::Relaxed) {
                let _guard = warmup_lock.lock().await;
                if warmup_complete.load(Ordering::Relaxed) {
                    scheduler.wait().await;
                    checker.check(&username).await
                } else {
                    scheduler.wait().await;
                    let result = checker.check(&username).await;
                    if !matches!(
                        result.status,
                        CheckStatus::RateLimited | CheckStatus::NetworkError
                    ) {
                        warmup_complete.store(true, Ordering::Relaxed);
                    }
                    result
                }
            } else {
                scheduler.wait().await;
                checker.check(&username).await
            };

            if result.status != CheckStatus::RateLimited {
                break result;
            }

            let delay = Duration::from_secs_f64(result.retry_after.unwrap_or(1.0).max(0.0));
            scheduler.defer(delay).await;
            let _ = event_tx.send(EngineEvent::Cooldown {
                seconds: delay.as_secs_f64(),
                detail: result.error.clone(),
            });
            worker_event(
                &event_tx,
                worker_id,
                WorkerState::Waiting,
                Some(username.clone()),
                result.proxy.clone(),
                Some(format!("rate limit · {:.1}s", delay.as_secs_f64())),
            );

            if rate_retries >= 1 {
                break result;
            }
            rate_retries += 1;
        };

        worker_event(
            &event_tx,
            worker_id,
            WorkerState::Idle,
            None,
            result.proxy.clone(),
            Some(result.status.as_str().into()),
        );
        if result_tx.send(result).is_err() {
            break;
        }
    }
    worker_event(
        &event_tx,
        worker_id,
        WorkerState::Stopped,
        None,
        None,
        None,
    );
}

fn worker_event(
    tx: &mpsc::UnboundedSender<EngineEvent>,
    id: usize,
    state: WorkerState,
    username: Option<String>,
    proxy: Option<String>,
    detail: Option<String>,
) {
    let _ = tx.send(EngineEvent::Worker(WorkerSnapshot {
        id,
        state,
        username,
        proxy,
        detail,
    }));
}
