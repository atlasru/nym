use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

pub const DEFAULT_CHARSET: &str = "abcdefghijklmnopqrstuvwxyz0123456789_.";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct ScannerConfig {
    pub mode: String,
    pub length: usize,
    pub charset: String,
    pub pattern: String,
    pub dictionary_file: String,
    pub workers: usize,
    pub queue_size: usize,
    pub interval: f64,
    pub jitter: f64,
    pub seed: Option<u64>,
    pub limit: Option<u64>,
}

impl Default for ScannerConfig {
    fn default() -> Self {
        Self {
            mode: "random".into(),
            length: 4,
            charset: DEFAULT_CHARSET.into(),
            pattern: "@@@#".into(),
            dictionary_file: "dictionaries/words.txt".into(),
            workers: 4,
            queue_size: 512,
            interval: 0.1,
            jitter: 0.0,
            seed: None,
            limit: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct ProxyConfig {
    pub enabled: bool,
    pub file: String,
    pub fallback_direct: bool,
    pub cooldown_seconds: f64,
    pub dead_after_failures: usize,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            file: "proxies.txt".into(),
            fallback_direct: false,
            cooldown_seconds: 30.0,
            dead_after_failures: 6,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct InterfaceConfig {
    pub animations: bool,
    pub animation_speed: f64,
    pub reduced_motion: bool,
    pub ascii_logo: bool,
    pub compact: bool,
}

impl Default for InterfaceConfig {
    fn default() -> Self {
        Self {
            animations: true,
            animation_speed: 1.0,
            reduced_motion: false,
            ascii_logo: true,
            compact: false,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct AppConfig {
    pub scanner: ScannerConfig,
    pub proxies: ProxyConfig,
    pub interface: InterfaceConfig,
}

impl AppConfig {
    pub fn load(path: &Path) -> Result<Self> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let text = fs::read_to_string(path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let config: Self = toml::from_str(&text)
            .with_context(|| format!("failed to parse {}", path.display()))?;
        config.validate()?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<()> {
        if !matches!(self.scanner.mode.as_str(), "random" | "sequential" | "pattern" | "dictionary") {
            bail!("unsupported scanner mode: {}", self.scanner.mode);
        }
        if !(2..=32).contains(&self.scanner.length) {
            bail!("username length must be between 2 and 32");
        }
        if self.scanner.charset.is_empty() {
            bail!("charset must not be empty");
        }
        if self.scanner.workers == 0 {
            bail!("workers must be >= 1");
        }
        if self.scanner.queue_size < self.scanner.workers {
            bail!("queue_size must be >= workers");
        }
        if self.scanner.interval < 0.0 || self.scanner.jitter < 0.0 {
            bail!("interval and jitter must be >= 0");
        }
        if self.proxies.cooldown_seconds < 0.0 {
            bail!("proxy cooldown_seconds must be >= 0");
        }
        if self.proxies.dead_after_failures == 0 {
            bail!("proxy dead_after_failures must be >= 1");
        }
        if self.interface.animation_speed <= 0.0 {
            bail!("animation_speed must be > 0");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_python_reference() {
        let config = AppConfig::default();
        assert_eq!(config.scanner.mode, "random");
        assert_eq!(config.scanner.length, 4);
        assert_eq!(config.scanner.workers, 4);
        assert_eq!(config.scanner.queue_size, 512);
        assert_eq!(config.scanner.pattern, "@@@#");
        assert_eq!(config.proxies.file, "proxies.txt");
        assert!(!config.proxies.fallback_direct);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn rejects_invalid_queue_size() {
        let mut config = AppConfig::default();
        config.scanner.workers = 8;
        config.scanner.queue_size = 4;
        assert!(config.validate().is_err());
    }
}
