use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckStatus {
    Available,
    Taken,
    Invalid,
    RateLimited,
    NetworkError,
    Unknown,
}

impl CheckStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Available => "available",
            Self::Taken => "taken",
            Self::Invalid => "invalid",
            Self::RateLimited => "rate_limited",
            Self::NetworkError => "network_error",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyState {
    Ready,
    Active,
    Cooldown,
    Degraded,
    Dead,
    Blocked,
}

impl ProxyState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ready => "READY",
            Self::Active => "ACTIVE",
            Self::Cooldown => "COOLDOWN",
            Self::Degraded => "DEGRADED",
            Self::Dead => "DEAD",
            Self::Blocked => "BLOCKED",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkerState {
    Idle,
    Active,
    Waiting,
    Paused,
    Stopped,
}

impl WorkerState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "IDLE",
            Self::Active => "ACTIVE",
            Self::Waiting => "WAITING",
            Self::Paused => "PAUSED",
            Self::Stopped => "STOPPED",
        }
    }
}

#[derive(Debug, Clone)]
pub struct CheckResult {
    pub username: String,
    pub status: CheckStatus,
    pub checked_at: DateTime<Utc>,
    pub http_status: Option<u16>,
    pub retry_after: Option<f64>,
    pub error: Option<String>,
    pub proxy: Option<String>,
}

impl CheckResult {
    pub fn new(username: impl Into<String>, status: CheckStatus) -> Self {
        Self {
            username: username.into(),
            status,
            checked_at: Utc::now(),
            http_status: None,
            retry_after: None,
            error: None,
            proxy: None,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScanStats {
    pub generated: u64,
    pub checked: u64,
    pub available: u64,
    pub taken: u64,
    pub invalid: u64,
    pub rate_limited: u64,
    pub network_errors: u64,
    pub unknown: u64,
}

impl ScanStats {
    pub fn record(&mut self, status: CheckStatus) {
        self.checked += 1;
        match status {
            CheckStatus::Available => self.available += 1,
            CheckStatus::Taken => self.taken += 1,
            CheckStatus::Invalid => self.invalid += 1,
            CheckStatus::RateLimited => self.rate_limited += 1,
            CheckStatus::NetworkError => self.network_errors += 1,
            CheckStatus::Unknown => self.unknown += 1,
        }
    }

    pub fn errors(&self) -> u64 {
        self.network_errors + self.unknown
    }
}

#[derive(Debug, Clone)]
pub struct WorkerSnapshot {
    pub id: usize,
    pub state: WorkerState,
    pub username: Option<String>,
    pub proxy: Option<String>,
    pub detail: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ProxySnapshot {
    pub id: usize,
    pub display: String,
    pub state: ProxyState,
    pub latency_ms: Option<u64>,
    pub cooldown_remaining: Option<f64>,
    pub worker_id: Option<usize>,
    pub last_error: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stats_record_matches_reference_semantics() {
        let mut stats = ScanStats::default();
        stats.record(CheckStatus::Taken);
        stats.record(CheckStatus::Available);
        stats.record(CheckStatus::RateLimited);

        assert_eq!(stats.checked, 3);
        assert_eq!(stats.taken, 1);
        assert_eq!(stats.available, 1);
        assert_eq!(stats.rate_limited, 1);
    }
}
