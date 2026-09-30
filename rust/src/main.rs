#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

use anyhow::{Context, Result};
use nym::config::AppConfig;
use std::path::PathBuf;

slint::include_modules!();

fn app_root() -> Result<PathBuf> {
    let exe = std::env::current_exe().context("failed to resolve executable path")?;
    Ok(exe
        .parent()
        .map(PathBuf::from)
        .unwrap_or(std::env::current_dir().context("failed to resolve current directory")?))
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "nym=info".into()),
        )
        .with_target(false)
        .compact()
        .init();

    let root = app_root()?;
    let config_path = root.join("config.toml");
    let config = AppConfig::load(&config_path).unwrap_or_else(|error| {
        tracing::warn!(%error, "config load failed; using defaults");
        AppConfig::default()
    });

    let ui = AppWindow::new().context("failed to create Nym window")?;
    ui.set_status_text(
        format!(
            "Rust rewrite · {} workers · {:.3}s interval",
            config.scanner.workers, config.scanner.interval
        )
        .into(),
    );
    ui.set_proxy_text(
        if config.proxies.enabled {
            "Proxies ON"
        } else {
            "Proxies OFF"
        }
        .into(),
    );
    ui.run().context("Nym UI exited with an error")?;
    Ok(())
}
