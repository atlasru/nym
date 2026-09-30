use rand::Rng;
use std::time::Duration;
use tokio::{sync::Mutex, time::Instant};

#[derive(Debug)]
pub struct RateScheduler {
    interval: Duration,
    jitter: Duration,
    next_at: Mutex<Instant>,
}

impl RateScheduler {
    pub fn new(interval_seconds: f64, jitter_seconds: f64) -> Self {
        Self {
            interval: Duration::from_secs_f64(interval_seconds.max(0.0)),
            jitter: Duration::from_secs_f64(jitter_seconds.max(0.0)),
            next_at: Mutex::new(Instant::now()),
        }
    }

    pub async fn wait(&self) {
        let mut next_at = self.next_at.lock().await;
        let now = Instant::now();
        if *next_at > now {
            tokio::time::sleep_until(*next_at).await;
        }

        let jitter = if self.jitter.is_zero() {
            Duration::ZERO
        } else {
            let max = self.jitter.as_secs_f64();
            Duration::from_secs_f64(rand::thread_rng().gen_range(0.0..=max))
        };
        *next_at = Instant::now() + self.interval + jitter;
    }

    pub async fn defer(&self, delay: Duration) {
        let mut next_at = self.next_at.lock().await;
        let candidate = Instant::now() + delay;
        if candidate > *next_at {
            *next_at = candidate;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn defer_moves_window_forward() {
        let scheduler = RateScheduler::new(0.0, 0.0);
        let started = Instant::now();
        scheduler.defer(Duration::from_millis(20)).await;
        scheduler.wait().await;
        assert!(started.elapsed() >= Duration::from_millis(15));
    }
}
