use nym::{
    config::AppConfig,
    engine::{self, EngineCommand, EngineEvent},
    model::{CheckStatus, ProxySnapshot, ProxyState, ScanStats, WorkerSnapshot, WorkerState},
};
use ratatui::{
    Frame,
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Paragraph, Wrap},
};
use std::{
    collections::VecDeque,
    path::PathBuf,
    time::{Duration, Instant},
};
use tokio::{runtime::Runtime, sync::mpsc};

const BG: Color = Color::Rgb(10, 12, 16);
const PANEL: Color = Color::Rgb(16, 19, 25);
const BORDER: Color = Color::Rgb(43, 48, 59);
const TEXT: Color = Color::Rgb(226, 229, 235);
const MUTED: Color = Color::Rgb(132, 139, 152);
const AMBER: Color = Color::Rgb(241, 181, 91);
const GREEN: Color = Color::Rgb(156, 226, 111);
const RED: Color = Color::Rgb(255, 88, 126);
const VIOLET: Color = Color::Rgb(177, 126, 255);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    Idle,
    Initializing,
    Running,
    Paused,
    Cooldown,
    Finished,
    Failed,
}

pub struct App {
    pub config: AppConfig,
    pub should_quit: bool,
    root: PathBuf,
    phase: Phase,
    notice: String,
    stats: ScanStats,
    workers: Vec<WorkerSnapshot>,
    proxies: Vec<ProxySnapshot>,
    hits: VecDeque<String>,
    recent: VecDeque<String>,
    result_times: VecDeque<Instant>,
    started_at: Option<Instant>,
    cooldown_until: Option<Instant>,
    hit_flash_until: Option<Instant>,
    tick: u64,
    rate_limit_events: u64,
    command_tx: Option<mpsc::UnboundedSender<EngineCommand>>,
    event_tx: mpsc::UnboundedSender<EngineEvent>,
    event_rx: mpsc::UnboundedReceiver<EngineEvent>,
}

impl App {
    pub fn new(config: AppConfig, root: PathBuf) -> Self {
        let (event_tx, event_rx) = mpsc::unbounded_channel();
        let workers = (1..=config.scanner.workers)
            .map(|id| WorkerSnapshot {
                id,
                state: WorkerState::Idle,
                username: None,
                proxy: None,
                detail: None,
            })
            .collect();
        Self {
            config,
            should_quit: false,
            root,
            phase: Phase::Idle,
            notice: "ready · S to start".into(),
            stats: ScanStats::default(),
            workers,
            proxies: Vec::new(),
            hits: VecDeque::new(),
            recent: VecDeque::new(),
            result_times: VecDeque::new(),
            started_at: None,
            cooldown_until: None,
            hit_flash_until: None,
            tick: 0,
            rate_limit_events: 0,
            command_tx: None,
            event_tx,
            event_rx,
        }
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    pub fn start(&mut self, runtime: &Runtime) {
        if matches!(
            self.phase,
            Phase::Running | Phase::Paused | Phase::Cooldown | Phase::Initializing
        ) {
            return;
        }
        self.stats = ScanStats::default();
        self.hits.clear();
        self.recent.clear();
        self.result_times.clear();
        self.started_at = Some(Instant::now());
        self.cooldown_until = None;
        self.hit_flash_until = None;
        self.rate_limit_events = 0;
        self.phase = Phase::Initializing;
        self.notice = "initializing scan…".into();
        for worker in &mut self.workers {
            worker.state = WorkerState::Idle;
            worker.username = None;
            worker.proxy = None;
            worker.detail = None;
        }

        let (command_tx, command_rx) = mpsc::unbounded_channel();
        self.command_tx = Some(command_tx);
        let root = self.root.clone();
        let config = self.config.clone();
        let events = self.event_tx.clone();
        runtime.spawn(async move {
            engine::run_scan(root, config, events, command_rx).await;
        });
    }

    pub fn toggle_pause(&mut self) {
        let Some(tx) = &self.command_tx else {
            return;
        };
        match self.phase {
            Phase::Running | Phase::Cooldown => {
                let _ = tx.send(EngineCommand::Pause);
            }
            Phase::Paused => {
                let _ = tx.send(EngineCommand::Resume);
            }
            _ => {}
        }
    }

    pub fn stop(&mut self) {
        if let Some(tx) = &self.command_tx {
            let _ = tx.send(EngineCommand::Stop);
        }
        if !matches!(self.phase, Phase::Idle | Phase::Finished | Phase::Failed) {
            self.notice = "stopping…".into();
        }
    }

    pub fn quit(&mut self) {
        self.stop();
        self.should_quit = true;
    }

    pub fn tick(&mut self) {
        self.tick = self.tick.wrapping_add(1);
        let now = Instant::now();
        while self
            .result_times
            .front()
            .is_some_and(|instant| now.duration_since(*instant) > Duration::from_secs(5))
        {
            self.result_times.pop_front();
        }
        if self.phase == Phase::Cooldown
            && self.cooldown_until.is_some_and(|until| until <= now)
        {
            self.phase = Phase::Running;
            self.cooldown_until = None;
            self.notice = "cooldown complete · resuming".into();
        }
    }

    pub fn drain_events(&mut self) {
        while let Ok(event) = self.event_rx.try_recv() {
            match event {
                EngineEvent::Notice(message) => {
                    self.notice = message.clone();
                    self.push_recent(format!("· {message}"));
                }
                EngineEvent::Started => {
                    self.phase = Phase::Running;
                    self.notice = "scanner running".into();
                }
                EngineEvent::Paused(paused) => {
                    self.phase = if paused {
                        Phase::Paused
                    } else {
                        Phase::Running
                    };
                    self.notice = if paused {
                        "paused".into()
                    } else {
                        "resumed".into()
                    };
                }
                EngineEvent::Worker(snapshot) => {
                    if let Some(worker) = self
                        .workers
                        .iter_mut()
                        .find(|worker| worker.id == snapshot.id)
                    {
                        *worker = snapshot;
                    } else {
                        self.workers.push(snapshot);
                        self.workers.sort_by_key(|worker| worker.id);
                    }
                }
                EngineEvent::Proxies(snapshots) => {
                    self.proxies = snapshots;
                }
                EngineEvent::Result(result) => {
                    self.result_times.push_back(Instant::now());
                    match result.status {
                        CheckStatus::Available => {
                            self.hits.push_front(result.username.clone());
                            while self.hits.len() > 12 {
                                self.hits.pop_back();
                            }
                            self.hit_flash_until =
                                Some(Instant::now() + Duration::from_millis(1400));
                            self.push_recent(format!("★ HIT  {}", result.username));
                        }
                        CheckStatus::Taken => {
                            self.push_recent(format!("· taken  {}", result.username));
                        }
                        CheckStatus::Invalid => {
                            self.push_recent(format!("! invalid  {}", result.username));
                        }
                        CheckStatus::RateLimited => {
                            self.push_recent(format!("◇ 429  {}", result.username));
                        }
                        CheckStatus::NetworkError => {
                            self.push_recent(format!("× network  {}", result.username));
                        }
                        CheckStatus::Unknown => {
                            self.push_recent(format!("? unknown  {}", result.username));
                        }
                    }
                }
                EngineEvent::Stats(stats) => self.stats = stats,
                EngineEvent::Cooldown { seconds, detail } => {
                    self.rate_limit_events += 1;
                    self.phase = Phase::Cooldown;
                    self.cooldown_until =
                        Some(Instant::now() + Duration::from_secs_f64(seconds));
                    self.notice = match detail {
                        Some(detail) => format!("Discord cooldown · {seconds:.1}s · {detail}"),
                        None => format!("Discord cooldown · {seconds:.1}s"),
                    };
                }
                EngineEvent::Finished(message) => {
                    self.phase = Phase::Finished;
                    self.notice = message;
                    self.command_tx = None;
                }
                EngineEvent::Failed(message) => {
                    self.phase = Phase::Failed;
                    self.notice = message.clone();
                    self.push_recent(format!("× {message}"));
                    self.command_tx = None;
                }
            }
        }
    }

    fn push_recent(&mut self, line: String) {
        self.recent.push_front(line);
        while self.recent.len() > 16 {
            self.recent.pop_back();
        }
    }

    fn rate(&self) -> f64 {
        self.result_times.len() as f64 / 5.0
    }

    fn runtime(&self) -> Duration {
        self.started_at
            .map_or(Duration::ZERO, |started| started.elapsed())
    }

    fn cooldown_remaining(&self) -> Option<Duration> {
        self.cooldown_until
            .map(|until| until.saturating_duration_since(Instant::now()))
    }
}

pub fn render(frame: &mut Frame<'_>, app: &App) {
    frame.render_widget(
        Block::default().style(Style::default().bg(BG)),
        frame.area(),
    );
    let area = frame.area();
    let outer = Rect {
        x: area.x.saturating_add(1),
        y: area.y,
        width: area.width.saturating_sub(2),
        height: area.height,
    };
    let sections = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(3),
            Constraint::Length(4),
            Constraint::Min(12),
            Constraint::Length(6),
            Constraint::Length(2),
        ])
        .split(outer);

    render_header(frame, sections[0], app);
    render_stats(frame, sections[1], app);
    render_dashboard(frame, sections[2], app);
    render_hits(frame, sections[3], app);
    render_footer(frame, sections[4], app);
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let (phase, phase_color) = match app.phase {
        Phase::Idle => ("IDLE", MUTED),
        Phase::Initializing => ("INIT", AMBER),
        Phase::Running => ("SCANNING", GREEN),
        Phase::Paused => ("PAUSED", AMBER),
        Phase::Cooldown => ("COOLDOWN", AMBER),
        Phase::Finished => ("FINISHED", GREEN),
        Phase::Failed => ("FAILED", RED),
    };
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "NYM",
                Style::default().fg(AMBER).add_modifier(Modifier::BOLD),
            ),
            Span::styled(" // SCANNER   ", Style::default().fg(MUTED)),
            Span::styled(
                phase,
                Style::default()
                    .fg(phase_color)
                    .add_modifier(Modifier::BOLD),
            ),
        ]),
        Line::from(Span::styled(
            app.notice.as_str(),
            Style::default().fg(MUTED),
        )),
    ];
    frame.render_widget(Paragraph::new(lines).style(Style::default().bg(BG)), area);
}

fn render_stats(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let proxy_text = if app.config.proxies.enabled {
        format!("{}/{}", ready_proxy_count(&app.proxies), app.proxies.len())
    } else {
        "OFF".into()
    };
    let runtime = app.runtime();
    let runtime_text = format!(
        "{:02}:{:02}",
        runtime.as_secs() / 60,
        runtime.as_secs() % 60
    );
    let stats = Line::from(vec![
        metric("CHECKED", &app.stats.checked.to_string()),
        separator(),
        metric_green("HITS", &app.stats.available.to_string()),
        separator(),
        metric("TAKEN", &app.stats.taken.to_string()),
        separator(),
        metric_red("ERRORS", &app.stats.errors().to_string()),
        separator(),
        metric("429", &app.rate_limit_events.to_string()),
        separator(),
        metric("RATE", &format!("{:.1}/s", app.rate())),
        separator(),
        metric("PROXIES", &proxy_text),
        separator(),
        metric("TIME", &runtime_text),
    ]);
    frame.render_widget(
        Paragraph::new(stats)
            .block(panel_block(" STATUS "))
            .style(Style::default().bg(PANEL)),
        area,
    );
}

fn render_dashboard(frame: &mut Frame<'_>, area: Rect, app: &App) {
    if area.width >= 105 {
        let columns = Layout::default()
            .direction(Direction::Horizontal)
            .constraints([
                Constraint::Percentage(34),
                Constraint::Percentage(32),
                Constraint::Percentage(34),
            ])
            .split(area);
        render_workers(frame, columns[0], app);
        render_stage(frame, columns[1], app);
        render_proxies(frame, columns[2], app);
    } else {
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Percentage(40),
                Constraint::Percentage(25),
                Constraint::Percentage(35),
            ])
            .split(area);
        render_workers(frame, rows[0], app);
        render_stage(frame, rows[1], app);
        render_proxies(frame, rows[2], app);
    }
}

fn render_workers(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let inner_height = area.height.saturating_sub(2) as usize;
    let worker_rows = app
        .workers
        .len()
        .min(inner_height.saturating_div(2).max(1));
    let mut lines = Vec::new();
    for worker in app.workers.iter().take(worker_rows) {
        let color = worker_color(worker.state);
        let mut spans = vec![
            Span::styled(format!("W{:02} ", worker.id), Style::default().fg(TEXT)),
            Span::styled(
                format!("{} {}", worker_symbol(worker.state), worker.state.as_str()),
                Style::default().fg(color).add_modifier(Modifier::BOLD),
            ),
        ];
        if let Some(username) = &worker.username {
            spans.push(Span::styled(
                format!("  {username}"),
                Style::default().fg(TEXT),
            ));
        }
        lines.push(Line::from(spans));
    }
    if !app.recent.is_empty() && lines.len() < inner_height {
        lines.push(Line::from(Span::styled(
            "─ recent",
            Style::default().fg(BORDER),
        )));
        let remaining = inner_height.saturating_sub(lines.len());
        for entry in app.recent.iter().take(remaining) {
            let color = if entry.starts_with('★') {
                GREEN
            } else if entry.starts_with('×') || entry.starts_with('!') {
                RED
            } else if entry.starts_with('◇') {
                AMBER
            } else {
                MUTED
            };
            lines.push(Line::from(Span::styled(
                entry.as_str(),
                Style::default().fg(color),
            )));
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" WORKERS / LOG "))
            .style(Style::default().bg(PANEL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_stage(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let mut lines = Vec::new();
    let hit_flash = app
        .hit_flash_until
        .is_some_and(|until| until > Instant::now());
    let (art, art_color) = if hit_flash {
        (HIT_FRAME, GREEN)
    } else {
        animation_frame(app)
    };
    lines.push(Line::from(""));
    for &row in art {
        lines.push(Line::from(Span::styled(
            row,
            Style::default().fg(art_color),
        )));
    }
    lines.push(Line::from(""));

    let status = match app.phase {
        Phase::Cooldown => app
            .cooldown_remaining()
            .map(|remaining| {
                format!(
                    "WAIT {:02}:{:02}",
                    remaining.as_secs() / 60,
                    remaining.as_secs() % 60
                )
            })
            .unwrap_or_else(|| "WAIT".into()),
        Phase::Running => "SCANNING".into(),
        Phase::Paused => "PAUSED".into(),
        Phase::Initializing => "INITIALIZING".into(),
        Phase::Finished => "COMPLETE".into(),
        Phase::Failed => "ERROR".into(),
        Phase::Idle => "ENGINE IDLE".into(),
    };
    lines.push(Line::from(Span::styled(
        status,
        Style::default().fg(AMBER).add_modifier(Modifier::BOLD),
    )));
    lines.push(Line::from(Span::styled(
        format!(
            "{} · {} workers · {:.3}s",
            app.config.scanner.mode.to_uppercase(),
            app.config.scanner.workers,
            app.config.scanner.interval
        ),
        Style::default().fg(MUTED),
    )));

    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" NYM "))
            .alignment(Alignment::Center)
            .style(Style::default().bg(PANEL)),
        area,
    );
}

fn render_proxies(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let inner_height = area.height.saturating_sub(2) as usize;
    let mut lines = Vec::new();
    if !app.config.proxies.enabled {
        lines.push(Line::from(Span::styled(
            "○ DISABLED",
            Style::default().fg(MUTED),
        )));
        lines.push(Line::from(Span::styled(
            app.config.proxies.file.as_str(),
            Style::default().fg(TEXT),
        )));
    } else if app.proxies.is_empty() {
        lines.push(Line::from(Span::styled(
            "… loading pool",
            Style::default().fg(AMBER),
        )));
    } else {
        for proxy in app.proxies.iter().take(inner_height) {
            let color = proxy_color(proxy.state);
            let latency = proxy
                .latency_ms
                .map(|value| format!(" {value}ms"))
                .unwrap_or_default();
            let mut text = format!(
                "#{:02} {} {}{}",
                proxy.id,
                proxy_symbol(proxy.state),
                proxy.state.as_str(),
                latency
            );
            if let Some(worker) = proxy.worker_id {
                text.push_str(&format!(" W{worker:02}"));
            }
            lines.push(Line::from(Span::styled(text, Style::default().fg(color))));
            if let Some(error) = &proxy.last_error
                && lines.len() < inner_height
            {
                lines.push(Line::from(Span::styled(
                    error.as_str(),
                    Style::default().fg(MUTED),
                )));
            }
        }
    }
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" PROXY POOL "))
            .style(Style::default().bg(PANEL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_hits(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let inner_height = area.height.saturating_sub(2) as usize;
    let lines = if app.hits.is_empty() {
        vec![Line::from(Span::styled(
            "No hits yet",
            Style::default().fg(MUTED),
        ))]
    } else {
        app.hits
            .iter()
            .take(inner_height)
            .map(|hit| {
                Line::from(vec![
                    Span::styled("★ ", Style::default().fg(GREEN)),
                    Span::styled(
                        hit.as_str(),
                        Style::default().fg(TEXT).add_modifier(Modifier::BOLD),
                    ),
                ])
            })
            .collect()
    };
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" HITS "))
            .style(Style::default().bg(PANEL)),
        area,
    );
}

fn render_footer(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let mut spans = Vec::new();
    if matches!(app.phase, Phase::Idle | Phase::Finished | Phase::Failed) {
        spans.extend(key_hint(" S ", "start"));
    } else {
        let pause_label = if app.phase == Phase::Paused {
            "resume"
        } else {
            "pause"
        };
        spans.extend(key_hint(" P ", pause_label));
        spans.extend(key_hint(" X ", "stop"));
    }
    spans.extend(key_hint(" Q ", "quit"));
    spans.push(Span::styled(
        "   config.toml controls mode / workers / interval / proxies",
        Style::default().fg(MUTED),
    ));
    frame.render_widget(
        Paragraph::new(Line::from(spans)).style(Style::default().bg(BG)),
        area,
    );
}

fn animation_frame(app: &App) -> (&'static [&'static str; 5], Color) {
    if !app.config.interface.animations || app.config.interface.reduced_motion {
        return (&IDLE_FRAME, VIOLET);
    }
    match app.phase {
        Phase::Running | Phase::Initializing => {
            let scaled =
                (app.tick as f64 * app.config.interface.animation_speed).round() as usize;
            (&SCAN_FRAMES[scaled % SCAN_FRAMES.len()], VIOLET)
        }
        Phase::Cooldown => {
            let scaled = (app.tick as usize / 2) % COOLDOWN_FRAMES.len();
            (&COOLDOWN_FRAMES[scaled], AMBER)
        }
        Phase::Paused => (&PAUSED_FRAME, AMBER),
        Phase::Failed => (&ERROR_FRAME, RED),
        _ => (&IDLE_FRAME, VIOLET),
    }
}

const IDLE_FRAME: [&str; 5] = [
    "     ·     ",
    "   ▄███▄   ",
    "  █  ◉  █  ",
    "   ▀███▀   ",
    "     ·     ",
];
const PAUSED_FRAME: [&str; 5] = [
    "           ",
    "   ▄███▄   ",
    "  █  ─  █  ",
    "   ▀███▀   ",
    "   ▪   ▪   ",
];
const ERROR_FRAME: [&str; 5] = [
    "  ░     ░  ",
    "    ▄█▄    ",
    " ░ █ × █ ░ ",
    "    ▀█▀    ",
    "  ░     ░  ",
];
const HIT_FRAME: &[&str; 5] = &[
    "  ✦  ░  ✦  ",
    "   ▄███▄   ",
    "  █  ★  █  ",
    "   ▀███▀   ",
    "  ✦ HIT ✦  ",
];
const SCAN_FRAMES: [[&str; 5]; 4] = [
    [
        "     ·     ",
        "   ▄███▄   ",
        "  █ ◉   █  ",
        "   ▀███▀   ",
        " ░       ░ ",
    ],
    [
        " ░       · ",
        "   ▄███▄   ",
        "  █  ◉  █  ",
        "   ▀███▀   ",
        "     ░     ",
    ],
    [
        " ·       ░ ",
        "   ▄███▄   ",
        "  █   ◉ █  ",
        "   ▀███▀   ",
        " ░       · ",
    ],
    [
        "     ░     ",
        "   ▄███▄   ",
        "  █  ◉  █  ",
        "   ▀███▀   ",
        " ·       ░ ",
    ],
];
const COOLDOWN_FRAMES: [[&str; 5]; 2] = [
    [
        "     ◷     ",
        "   ▄███▄   ",
        "  █  ·  █  ",
        "   ▀███▀   ",
        "   ░   ░   ",
    ],
    [
        "     ◴     ",
        "   ▄███▄   ",
        "  █  ·  █  ",
        "   ▀███▀   ",
        "     ░     ",
    ],
];

fn ready_proxy_count(proxies: &[ProxySnapshot]) -> usize {
    proxies
        .iter()
        .filter(|proxy| matches!(proxy.state, ProxyState::Ready | ProxyState::Active))
        .count()
}

fn worker_symbol(state: WorkerState) -> &'static str {
    match state {
        WorkerState::Idle => "○",
        WorkerState::Active => "●",
        WorkerState::Waiting => "◷",
        WorkerState::Paused => "Ⅱ",
        WorkerState::Stopped => "×",
    }
}

fn worker_color(state: WorkerState) -> Color {
    match state {
        WorkerState::Active => GREEN,
        WorkerState::Waiting | WorkerState::Paused => AMBER,
        WorkerState::Stopped => RED,
        WorkerState::Idle => MUTED,
    }
}

fn proxy_symbol(state: ProxyState) -> &'static str {
    match state {
        ProxyState::Ready => "●",
        ProxyState::Active => "◆",
        ProxyState::Cooldown => "◷",
        ProxyState::Degraded => "!",
        ProxyState::Dead => "×",
        ProxyState::Blocked => "■",
    }
}

fn proxy_color(state: ProxyState) -> Color {
    match state {
        ProxyState::Ready | ProxyState::Active => GREEN,
        ProxyState::Cooldown | ProxyState::Degraded => AMBER,
        ProxyState::Dead | ProxyState::Blocked => RED,
    }
}

fn panel_block(title: &'static str) -> Block<'static> {
    Block::default()
        .borders(Borders::ALL)
        .border_style(Style::default().fg(BORDER))
        .title(Span::styled(
            title,
            Style::default().fg(AMBER).add_modifier(Modifier::BOLD),
        ))
        .style(Style::default().bg(PANEL))
}

fn metric(label: &'static str, value: &str) -> Span<'static> {
    Span::styled(
        format!(" {label} {value} "),
        Style::default().fg(TEXT).add_modifier(Modifier::BOLD),
    )
}

fn metric_green(label: &'static str, value: &str) -> Span<'static> {
    Span::styled(
        format!(" {label} {value} "),
        Style::default().fg(GREEN).add_modifier(Modifier::BOLD),
    )
}

fn metric_red(label: &'static str, value: &str) -> Span<'static> {
    Span::styled(
        format!(" {label} {value} "),
        Style::default().fg(RED).add_modifier(Modifier::BOLD),
    )
}

fn separator() -> Span<'static> {
    Span::styled("│", Style::default().fg(BORDER))
}

fn key_hint(key: &'static str, label: &'static str) -> Vec<Span<'static>> {
    vec![
        Span::styled(
            key,
            Style::default()
                .fg(BG)
                .bg(AMBER)
                .add_modifier(Modifier::BOLD),
        ),
        Span::styled(format!(" {label}   "), Style::default().fg(TEXT)),
    ]
}
