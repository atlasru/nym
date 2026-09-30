mod tui;

use anyhow::{Context, Result};
use crossterm::{
    event::{self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEventKind},
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use nym::config::AppConfig;
use ratatui::{Terminal, backend::CrosstermBackend};
use std::{
    fs::OpenOptions,
    io::{self, Stdout},
    path::PathBuf,
    time::Duration,
};
use tokio::runtime::Builder;
use tui::{App, Phase};

struct TerminalGuard {
    terminal: Terminal<CrosstermBackend<Stdout>>,
}

impl TerminalGuard {
    fn new() -> Result<Self> {
        enable_raw_mode().context("failed to enable terminal raw mode")?;
        let mut stdout = io::stdout();
        execute!(stdout, EnterAlternateScreen, EnableMouseCapture)
            .context("failed to enter alternate screen")?;
        let backend = CrosstermBackend::new(stdout);
        let mut terminal = Terminal::new(backend).context("failed to initialize terminal")?;
        terminal.clear().context("failed to clear terminal")?;
        Ok(Self { terminal })
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        let _ = disable_raw_mode();
        let _ = execute!(
            self.terminal.backend_mut(),
            LeaveAlternateScreen,
            DisableMouseCapture
        );
        let _ = self.terminal.show_cursor();
    }
}

fn app_root() -> Result<PathBuf> {
    let exe = std::env::current_exe().context("failed to resolve executable path")?;
    Ok(exe
        .parent()
        .map(PathBuf::from)
        .unwrap_or(std::env::current_dir().context("failed to resolve current directory")?))
}

fn main() -> Result<()> {
    let root = app_root()?;
    let config_path = root.join("config.toml");
    let config = AppConfig::load(&config_path).unwrap_or_else(|error| {
        eprintln!("config load failed: {error:#}; using defaults");
        AppConfig::default()
    });
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(root.join("proxies.txt"))
        .context("failed to create proxies.txt")?;

    let runtime = Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("failed to create Tokio runtime")?;
    let mut app = App::new(config, root);
    let mut terminal = TerminalGuard::new()?;

    while !app.should_quit {
        app.drain_events();
        app.tick();
        terminal
            .terminal
            .draw(|frame| tui::render(frame, &app))
            .context("failed to draw Nym TUI")?;

        if event::poll(Duration::from_millis(100)).context("terminal event poll failed")?
            && let Event::Key(key) = event::read().context("terminal event read failed")?
            && key.kind == KeyEventKind::Press
        {
            match key.code {
                KeyCode::Char('s') | KeyCode::Char('S') => {
                    if matches!(app.phase(), Phase::Idle | Phase::Finished | Phase::Failed) {
                        app.start(&runtime);
                    }
                }
                KeyCode::Char('p') | KeyCode::Char('P') => app.toggle_pause(),
                KeyCode::Char('x') | KeyCode::Char('X') => app.stop(),
                KeyCode::Char('q') | KeyCode::Char('Q') | KeyCode::Esc => app.quit(),
                _ => {}
            }
        }
    }

    Ok(())
}
