#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckStatus {
    Available,
    Taken,
    Invalid,
    RateLimited,
    NetworkError,
    Unknown,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkerState {
    Idle,
    Active,
    Waiting,
    Paused,
    Stopped,
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
