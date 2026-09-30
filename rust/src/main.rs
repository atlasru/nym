use anyhow::{Context, Result};
use crossterm::{
    event::{self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEventKind},
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use nym::config::AppConfig;
use ratatui::{
    Frame, Terminal,
    backend::CrosstermBackend,
    layout::{Alignment, Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Paragraph, Wrap},
};
use std::{
    io::{self, Stdout},
    path::PathBuf,
    time::Duration,
};

const BG: Color = Color::Rgb(10, 12, 16);
const PANEL: Color = Color::Rgb(16, 19, 25);
const BORDER: Color = Color::Rgb(43, 48, 59);
const TEXT: Color = Color::Rgb(226, 229, 235);
const MUTED: Color = Color::Rgb(132, 139, 152);
const AMBER: Color = Color::Rgb(241, 181, 91);
const GREEN: Color = Color::Rgb(156, 226, 111);

struct Tui {
    terminal: Terminal<CrosstermBackend<Stdout>>,
}

impl Tui {
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

impl Drop for Tui {
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

#[derive(Debug)]
struct App {
    config: AppConfig,
    notice: Option<String>,
    should_quit: bool,
}

impl App {
    fn new(config: AppConfig, notice: Option<String>) -> Self {
        Self {
            config,
            notice,
            should_quit: false,
        }
    }

    fn workers(&self) -> usize {
        self.config.scanner.workers
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
    let (config, notice) = match AppConfig::load(&config_path) {
        Ok(config) => (config, None),
        Err(error) => (
            AppConfig::default(),
            Some(format!("config fallback: {error}")),
        ),
    };

    let mut app = App::new(config, notice);
    let mut tui = Tui::new()?;

    while !app.should_quit {
        tui.terminal
            .draw(|frame| render(frame, &app))
            .context("failed to draw Nym TUI")?;

        if event::poll(Duration::from_millis(120)).context("terminal event poll failed")?
            && let Event::Key(key) = event::read().context("terminal event read failed")?
            && key.kind == KeyEventKind::Press
        {
            match key.code {
                KeyCode::Char('q') | KeyCode::Char('Q') | KeyCode::Esc => app.should_quit = true,
                _ => {}
            }
        }
    }

    Ok(())
}

fn render(frame: &mut Frame<'_>, app: &App) {
    frame.render_widget(Block::default().style(Style::default().bg(BG)), frame.area());

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
            Constraint::Min(11),
            Constraint::Length(5),
            Constraint::Length(2),
        ])
        .split(outer);

    render_header(frame, sections[0], app);
    render_stats(frame, sections[1], app);
    render_dashboard(frame, sections[2], app);
    render_hits(frame, sections[3]);
    render_footer(frame, sections[4]);
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let subtitle = app.notice.as_deref().unwrap_or("Rust rewrite · terminal foundation");
    let lines = vec![
        Line::from(vec![
            Span::styled("NYM", Style::default().fg(AMBER).add_modifier(Modifier::BOLD)),
            Span::styled(" // SCANNER", Style::default().fg(MUTED)),
        ]),
        Line::from(Span::styled(subtitle, Style::default().fg(MUTED))),
    ];

    frame.render_widget(Paragraph::new(lines).style(Style::default().bg(BG)), area);
}

fn render_stats(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let proxy_state = if app.config.proxies.enabled { "ON" } else { "OFF" };
    let stats = Line::from(vec![
        metric("CHECKED", "0"),
        separator(),
        metric_green("HITS", "0"),
        separator(),
        metric("ERRORS", "0"),
        separator(),
        metric("RATE", "0.0/s"),
        separator(),
        metric("PROXIES", proxy_state),
    ]);

    let block = panel_block(" STATUS ");
    frame.render_widget(
        Paragraph::new(stats)
            .block(block)
            .alignment(Alignment::Left)
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
                Constraint::Percentage(42),
                Constraint::Percentage(24),
                Constraint::Percentage(34),
            ])
            .split(area);
        render_workers(frame, rows[0], app);
        render_stage(frame, rows[1], app);
        render_proxies(frame, rows[2], app);
    }
}

fn render_workers(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let visible = app.workers().min(8);
    let mut lines = Vec::with_capacity(visible + 1);
    for index in 0..visible {
        lines.push(Line::from(vec![
            Span::styled(format!("W{:02} ", index + 1), Style::default().fg(TEXT)),
            Span::styled("○ IDLE", Style::default().fg(MUTED)),
        ]));
    }
    if app.workers() > visible {
        lines.push(Line::from(Span::styled(
            format!("+{} more workers", app.workers() - visible),
            Style::default().fg(MUTED),
        )));
    }

    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" WORKERS "))
            .style(Style::default().bg(PANEL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_stage(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let mode = app.config.scanner.mode.to_uppercase();
    let text = vec![
        Line::from(""),
        Line::from(Span::styled(
            "ENGINE IDLE",
            Style::default().fg(AMBER).add_modifier(Modifier::BOLD),
        )),
        Line::from(Span::styled(
            format!("{mode} · {} workers", app.workers()),
            Style::default().fg(TEXT),
        )),
        Line::from(Span::styled(
            format!("{:.3}s interval", app.config.scanner.interval),
            Style::default().fg(MUTED),
        )),
    ];

    frame.render_widget(
        Paragraph::new(text)
            .block(panel_block(" NYM "))
            .alignment(Alignment::Center)
            .style(Style::default().bg(PANEL)),
        area,
    );
}

fn render_proxies(frame: &mut Frame<'_>, area: Rect, app: &App) {
    let (headline, detail, color) = if app.config.proxies.enabled {
        ("○ READY", app.config.proxies.file.as_str(), GREEN)
    } else {
        ("○ DISABLED", app.config.proxies.file.as_str(), MUTED)
    };

    let lines = vec![
        Line::from(Span::styled(headline, Style::default().fg(color))),
        Line::from(Span::styled(detail, Style::default().fg(TEXT))),
        Line::from(""),
        Line::from(Span::styled(
            "pool telemetry will appear here",
            Style::default().fg(MUTED),
        )),
    ];

    frame.render_widget(
        Paragraph::new(lines)
            .block(panel_block(" PROXY POOL "))
            .style(Style::default().bg(PANEL))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_hits(frame: &mut Frame<'_>, area: Rect) {
    frame.render_widget(
        Paragraph::new(Line::from(Span::styled(
            "No hits yet",
            Style::default().fg(MUTED),
        )))
        .block(panel_block(" HITS "))
        .style(Style::default().bg(PANEL)),
        area,
    );
}

fn render_footer(frame: &mut Frame<'_>, area: Rect) {
    let line = Line::from(vec![
        Span::styled(" Q ", Style::default().fg(BG).bg(AMBER).add_modifier(Modifier::BOLD)),
        Span::styled(" quit", Style::default().fg(TEXT)),
        Span::styled("   ESC ", Style::default().fg(MUTED)),
        Span::styled("quit", Style::default().fg(MUTED)),
        Span::styled(
            "   scanner controls unlock after engine parity",
            Style::default().fg(MUTED),
        ),
    ]);
    frame.render_widget(Paragraph::new(line).style(Style::default().bg(BG)), area);
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

fn separator() -> Span<'static> {
    Span::styled("│", Style::default().fg(BORDER))
}
