from __future__ import annotations

import asyncio
import copy
import time
from collections import deque
from pathlib import Path

import httpx
from textual.app import App, ComposeResult
from textual.containers import Horizontal, Vertical
from textual.screen import Screen
from textual.widgets import Button, Input, Label, RichLog, Select, Static

from .checker import UsernameChecker
from .config import AppConfig, AppPaths, ScannerConfig, load_config, save_config
from .engine import ScanEngine
from .models import CheckResult, CheckStatus
from .proxy import ProxyPool, ProxyState, normalize_proxy_url, redact_proxy_url
from .runtime import build_usernames
from .scheduler import RateScheduler
from .storage import Storage

PULSE_FRAMES = (
    "             ·\n\n             NYM",
    "          ·  ◇  ·\n\n             NYM",
    "       ·     ◆     ·\n\n             NYM",
    "    ·        ✦        ·\n\n             NYM",
    "       ·     ◆     ·\n\n             NYM",
    "          ·  ◇  ·\n\n             NYM",
    "             ✦\n\n             NYM",
)
ACTIVITY_FRAMES = ("·", "✦", "◆", "✦")


class SplashScreen(Screen):
    def __init__(self, config: AppConfig) -> None:
        super().__init__()
        self.config = config
        self._frame = 0

    def compose(self) -> ComposeResult:
        yield Static(PULSE_FRAMES[0], id="pulse")

    def on_mount(self) -> None:
        interface = self.config.interface
        if not interface.animations or interface.reduced_motion:
            self.set_timer(0.08, self._finish)
            return
        interval = max(0.06, 0.13 / interface.animation_speed)
        duration = max(0.45, 0.92 / interface.animation_speed)
        self.set_interval(interval, self._tick)
        self.set_timer(duration, self._finish)

    def _tick(self) -> None:
        self._frame = (self._frame + 1) % len(PULSE_FRAMES)
        self.query_one("#pulse", Static).update(PULSE_FRAMES[self._frame])

    def _finish(self) -> None:
        if self.app.screen is self:
            self.app.pop_screen()


class MainMenuScreen(Screen):
    def compose(self) -> ComposeResult:
        with Vertical(id="menu-card"):
            yield Static("✦  NYM", classes="brand")
            yield Static("fast, local username availability scanner", classes="muted")
            yield Button("New scan", id="new-scan", variant="primary")
            yield Button("Resume last config", id="resume")
            yield Button("Results", id="results")
            yield Button("Configuration", id="configuration")
            yield Button("Proxies", id="proxies")
            yield Button("Exit", id="exit")
            yield Static("↑↓ navigate   enter select", classes="hint")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        app = self.app
        if event.button.id == "new-scan":
            app.push_screen(ScanSetupScreen(copy.deepcopy(app.config.scanner)))
        elif event.button.id == "resume":
            app.push_screen(ScanScreen(copy.deepcopy(app.config.scanner), resume=True))
        elif event.button.id == "results":
            app.push_screen(ResultsScreen(app.paths.available))
        elif event.button.id == "configuration":
            app.push_screen(ConfigurationScreen())
        elif event.button.id == "proxies":
            app.push_screen(ProxyScreen())
        elif event.button.id == "exit":
            app.exit()


class BackScreen(Screen):
    BINDINGS = [("escape", "back", "Back")]

    def action_back(self) -> None:
        self.app.pop_screen()


class ScanSetupScreen(BackScreen):
    def __init__(self, scanner: ScannerConfig) -> None:
        super().__init__()
        self.scanner = scanner

    def compose(self) -> ComposeResult:
        with Vertical(classes="panel"):
            yield Static("✦  New scan", classes="title")
            yield Label("Mode")
            yield Select(
                [
                    ("Random", "random"),
                    ("Sequential", "sequential"),
                    ("Pattern", "pattern"),
                    ("Dictionary", "dictionary"),
                ],
                value=self.scanner.mode,
                allow_blank=False,
                id="mode",
            )
            yield Label("Length · 2–32")
            yield Input(str(self.scanner.length), id="length")
            yield Label("Charset")
            yield Input(self.scanner.charset, id="charset")
            yield Label("Pattern · @ letter, # digit, * charset")
            yield Input(self.scanner.pattern, id="pattern")
            yield Label("Dictionary file")
            yield Input(self.scanner.dictionary_file, id="dictionary")
            with Horizontal(classes="row"):
                with Vertical():
                    yield Label("Workers")
                    yield Input(str(self.scanner.workers), id="workers")
                with Vertical():
                    yield Label("Queue")
                    yield Input(str(self.scanner.queue_size), id="queue")
            with Horizontal(classes="row"):
                with Vertical():
                    yield Label("Interval, sec")
                    yield Input(str(self.scanner.interval), id="interval")
                with Vertical():
                    yield Label("Jitter, sec")
                    yield Input(str(self.scanner.jitter), id="jitter")
            yield Label("Stop after N checks · empty = unlimited")
            yield Input("" if self.scanner.limit is None else str(self.scanner.limit), id="limit")
            yield Static("", id="form-error", classes="error")
            with Horizontal(classes="actions"):
                yield Button("Start scan", id="start", variant="primary")
                yield Button("Back", id="back")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "back":
            self.app.pop_screen()
            return
        if event.button.id != "start":
            return
        try:
            scanner = self._read_scanner()
        except ValueError as exc:
            self.query_one("#form-error", Static).update(str(exc))
            return
        self.app.config.scanner = copy.deepcopy(scanner)
        save_config(self.app.paths.config, self.app.config)
        self.app.push_screen(ScanScreen(scanner))

    def _read_scanner(self) -> ScannerConfig:
        mode = str(self.query_one("#mode", Select).value)
        limit_text = self.query_one("#limit", Input).value.strip()
        scanner = ScannerConfig(
            mode=mode,
            length=int(self.query_one("#length", Input).value),
            charset=self.query_one("#charset", Input).value,
            pattern=self.query_one("#pattern", Input).value,
            dictionary_file=self.query_one("#dictionary", Input).value,
            workers=int(self.query_one("#workers", Input).value),
            queue_size=int(self.query_one("#queue", Input).value),
            interval=float(self.query_one("#interval", Input).value),
            jitter=float(self.query_one("#jitter", Input).value),
            seed=self.scanner.seed,
            limit=int(limit_text) if limit_text else None,
        )
        _validate_scanner(scanner)
        return scanner


class ConfigurationScreen(BackScreen):
    def __init__(self) -> None:
        super().__init__()
        self.animations = True
        self.reduced_motion = False

    def compose(self) -> ComposeResult:
        config = self.app.config
        self.animations = config.interface.animations
        self.reduced_motion = config.interface.reduced_motion
        with Vertical(classes="panel"):
            yield Static("✦  Configuration", classes="title")
            yield Label("Default workers")
            yield Input(str(config.scanner.workers), id="cfg-workers")
            yield Label("Default interval, sec")
            yield Input(str(config.scanner.interval), id="cfg-interval")
            yield Label("Default jitter, sec")
            yield Input(str(config.scanner.jitter), id="cfg-jitter")
            yield Label("Animation speed")
            yield Input(str(config.interface.animation_speed), id="cfg-animation-speed")
            yield Button(self._animation_label(), id="toggle-animations")
            yield Button(self._motion_label(), id="toggle-motion")
            yield Static("", id="cfg-error", classes="error")
            with Horizontal(classes="actions"):
                yield Button("Save", id="save", variant="primary")
                yield Button("Back", id="back")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id
        if button_id == "back":
            self.app.pop_screen()
            return
        if button_id == "toggle-animations":
            self.animations = not self.animations
            event.button.label = self._animation_label()
            return
        if button_id == "toggle-motion":
            self.reduced_motion = not self.reduced_motion
            event.button.label = self._motion_label()
            return
        if button_id != "save":
            return
        try:
            workers = int(self.query_one("#cfg-workers", Input).value)
            interval = float(self.query_one("#cfg-interval", Input).value)
            jitter = float(self.query_one("#cfg-jitter", Input).value)
            speed = float(self.query_one("#cfg-animation-speed", Input).value)
            if workers < 1 or interval < 0 or jitter < 0 or speed <= 0:
                raise ValueError("values must be positive; interval and jitter may be zero")
        except ValueError as exc:
            self.query_one("#cfg-error", Static).update(str(exc))
            return
        config = self.app.config
        config.scanner.workers = workers
        config.scanner.interval = interval
        config.scanner.jitter = jitter
        config.interface.animations = self.animations
        config.interface.reduced_motion = self.reduced_motion
        config.interface.animation_speed = speed
        save_config(self.app.paths.config, config)
        self.app.pop_screen()

    def _animation_label(self) -> str:
        return f"Animations: {'ON' if self.animations else 'OFF'}"

    def _motion_label(self) -> str:
        return f"Reduced motion: {'ON' if self.reduced_motion else 'OFF'}"


class ProxyScreen(BackScreen):
    def __init__(self) -> None:
        super().__init__()
        self.testing_task: asyncio.Task[None] | None = None

    def compose(self) -> ComposeResult:
        enabled = self.app.config.proxies.enabled
        with Vertical(classes="panel wide"):
            yield Static("✦  Proxy manager", classes="title")
            yield Static(
                "proxies.txt supports HTTP / HTTPS / SOCKS5 and optional auth",
                classes="muted",
            )
            yield Button(
                f"Proxy routing: {'ON' if enabled else 'OFF'}",
                id="toggle-proxies",
                variant="primary" if enabled else "default",
            )
            with Horizontal(classes="row"):
                yield Input("", placeholder="host:port or scheme://user:pass@host:port", id="proxy")
                yield Button("Add", id="add-proxy")
                yield Button("Reload", id="reload-proxies")
                yield Button("Test", id="test-proxies")
            yield RichLog(id="proxy-log", markup=True, wrap=True)
            yield Button("Back", id="back")

    def on_mount(self) -> None:
        self._render_proxies()

    def on_unmount(self) -> None:
        if self.testing_task is not None:
            self.testing_task.cancel()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id
        if button_id == "back":
            self.app.pop_screen()
            return
        if button_id == "toggle-proxies":
            config = self.app.config.proxies
            config.enabled = not config.enabled
            save_config(self.app.paths.config, self.app.config)
            event.button.label = f"Proxy routing: {'ON' if config.enabled else 'OFF'}"
            return
        if button_id == "reload-proxies":
            self._render_proxies()
            return
        if button_id == "add-proxy":
            self._add_proxy()
            return
        if button_id == "test-proxies":
            if self.testing_task is None or self.testing_task.done():
                self.testing_task = asyncio.create_task(self._test_proxies())

    def _render_proxies(self) -> None:
        log = self.query_one("#proxy-log", RichLog)
        log.clear()
        path = self.app.paths.proxies
        lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
        valid = 0
        invalid = 0
        for raw in lines:
            value = raw.strip()
            if not value or value.startswith("#"):
                continue
            try:
                normalized = normalize_proxy_url(value)
            except ValueError as exc:
                invalid += 1
                log.write(f"[red]×[/] {value} · {exc}")
                continue
            valid += 1
            log.write(f"[dim]◇[/] {redact_proxy_url(normalized)}")
        log.write(f"\n[bold]{valid} valid[/] · {invalid} invalid · {path.name}")

    def _add_proxy(self) -> None:
        field = self.query_one("#proxy", Input)
        value = field.value.strip()
        if not value:
            return
        try:
            normalize_proxy_url(value)
        except ValueError as exc:
            self.query_one("#proxy-log", RichLog).write(f"[red]×[/] {exc}")
            return
        with self.app.paths.proxies.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(value + "\n")
        field.value = ""
        self._render_proxies()

    async def _test_proxies(self) -> None:
        log = self.query_one("#proxy-log", RichLog)
        pool = _make_proxy_pool(self.app.config, self.app.paths)
        if not pool.endpoints:
            log.write("[yellow]No proxies to test.[/]")
            return
        log.write(f"\n[bold]Testing {len(pool.endpoints)} proxies…[/]")
        async with pool:
            await pool.health_check_all()
        for endpoint in pool.endpoints:
            latency = "—" if endpoint.latency_ms is None else f"{endpoint.latency_ms:.0f} ms"
            icon = "✓" if endpoint.state is ProxyState.READY else "×"
            color = "green" if endpoint.state is ProxyState.READY else "red"
            detail = endpoint.last_error or latency
            log.write(f"[{color}]{icon}[/] {endpoint.display} · {detail}")


class ResultsScreen(BackScreen):
    def __init__(self, available_file: Path) -> None:
        super().__init__()
        self.available_file = available_file

    def compose(self) -> ComposeResult:
        with Vertical(classes="panel wide"):
            yield Static("✦  Available usernames", classes="title")
            yield RichLog(id="results-log", markup=True, wrap=True)
            yield Button("Back", id="back")

    def on_mount(self) -> None:
        log = self.query_one("#results-log", RichLog)
        if not self.available_file.exists():
            log.write("[dim]No hits yet.[/]")
            return
        values = [
            line.strip()
            for line in self.available_file.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        if not values:
            log.write("[dim]No hits yet.[/]")
            return
        for username in values[-200:]:
            log.write(f"[bold green]◆[/] {username}")
        log.write(f"\n[dim]{len(values)} total[/]")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "back":
            self.app.pop_screen()


class ScanScreen(Screen):
    BINDINGS = [
        ("space", "pause_resume", "Pause / resume"),
        ("escape", "stop_or_back", "Stop / back"),
    ]

    def __init__(self, scanner: ScannerConfig, *, resume: bool = False) -> None:
        super().__init__()
        self.scanner = scanner
        self.resume = resume
        self.engine: ScanEngine | None = None
        self.scan_task: asyncio.Task[None] | None = None
        self.started_at = 0.0
        self.finished = False
        self.current_username = "—"
        self.result_times: deque[float] = deque()
        self.activity_index = 0

    def compose(self) -> ComposeResult:
        with Vertical(classes="panel wide scan-panel"):
            yield Static("✦  Nym scan", classes="title")
            yield Static("Starting…", id="scan-status", classes="muted")
            yield Static("", id="scan-stats")
            yield Static("", id="hit-flash")
            yield RichLog(id="scan-log", markup=True, wrap=True)
            with Horizontal(classes="actions"):
                yield Button("Pause", id="pause")
                yield Button("Stop", id="stop", variant="error")
                yield Button("Back", id="back", disabled=True)

    def on_mount(self) -> None:
        self.started_at = time.monotonic()
        self.set_interval(0.2, self._refresh_stats)
        self.scan_task = asyncio.create_task(self._run_scan())

    def on_unmount(self) -> None:
        if self.engine is not None:
            self.engine.stop()
        if self.scan_task is not None and not self.scan_task.done():
            self.scan_task.cancel()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id
        if button_id == "pause":
            self.action_pause_resume()
        elif button_id == "stop":
            self._stop_scan()
        elif button_id == "back" and self.finished:
            self.app.pop_screen()

    def action_pause_resume(self) -> None:
        if self.engine is None or self.finished:
            return
        button = self.query_one("#pause", Button)
        if self.engine.paused:
            self.engine.resume()
            button.label = "Pause"
            self.query_one("#scan-status", Static).update("✦ Scanning")
        else:
            self.engine.pause()
            button.label = "Resume"
            self.query_one("#scan-status", Static).update("◇ Paused")

    def action_stop_or_back(self) -> None:
        if self.finished:
            self.app.pop_screen()
        else:
            self._stop_scan()

    def _stop_scan(self) -> None:
        if self.engine is not None:
            self.engine.stop()
        self.query_one("#scan-status", Static).update("◌ Stopping…")

    async def _run_scan(self) -> None:
        log = self.query_one("#scan-log", RichLog)
        proxy_pool: ProxyPool | None = None
        had_error = False
        try:
            usernames = build_usernames(self.scanner, self.app.paths.root)
            if self.app.config.proxies.enabled:
                proxy_pool = _make_proxy_pool(self.app.config, self.app.paths)
                if proxy_pool.endpoints:
                    await proxy_pool.open()
                    log.write(f"[dim]Proxy pool: {len(proxy_pool.endpoints)} loaded[/]")
                elif not proxy_pool.fallback_direct:
                    raise RuntimeError(
                        "proxy routing is enabled but proxies.txt has no valid proxies"
                    )

            async with httpx.AsyncClient() as client:
                checker = UsernameChecker(client, proxy_pool=proxy_pool)
                scheduler = RateScheduler(
                    self.scanner.interval,
                    jitter=self.scanner.jitter,
                    seed=self.scanner.seed,
                )
                async with Storage(
                    self.app.paths.database,
                    available_file=self.app.paths.available,
                ) as storage:
                    self.engine = ScanEngine(
                        checker,
                        storage,
                        scheduler,
                        workers=self.scanner.workers,
                        queue_size=self.scanner.queue_size,
                    )
                    label = "Resuming" if self.resume else "Scanning"
                    self.query_one("#scan-status", Static).update(f"✦ {label}")
                    async for result in self.engine.run(usernames):
                        self._record_result(result, log)
                        if self.scanner.limit is not None:
                            if self.engine.stats.checked >= self.scanner.limit:
                                self.engine.stop()
                                break
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            had_error = True
            log.write(f"[red]× {type(exc).__name__}: {exc}[/]")
            self.query_one("#scan-status", Static).update("× Error")
        finally:
            if proxy_pool is not None:
                await proxy_pool.close()
            self.finished = True
            if not had_error:
                self.query_one("#scan-status", Static).update("✓ Finished")
            self.query_one("#pause", Button).disabled = True
            self.query_one("#stop", Button).disabled = True
            self.query_one("#back", Button).disabled = False

    def _record_result(self, result: CheckResult, log: RichLog) -> None:
        self.current_username = result.username
        now = time.monotonic()
        self.result_times.append(now)
        while self.result_times and now - self.result_times[0] > 5.0:
            self.result_times.popleft()

        if result.status is CheckStatus.AVAILABLE:
            log.write(f"[bold green]◆ AVAILABLE[/]  {result.username}")
            flash = self.query_one("#hit-flash", Static)
            flash.update(f"◆  AVAILABLE  {result.username}")
            self.set_timer(0.65, lambda: flash.update(""))
        elif result.status is CheckStatus.TAKEN:
            log.write(f"[dim]· taken[/]      {result.username}")
        elif result.status is CheckStatus.RATE_LIMITED:
            log.write(f"[yellow]◇ rate limit[/] {result.username}")
        elif result.status is CheckStatus.NETWORK_ERROR:
            log.write(f"[red]× network[/]    {result.username}")
        elif result.status is CheckStatus.INVALID:
            log.write(f"[yellow]× invalid[/]    {result.username}")
        else:
            log.write(f"[red]× unknown[/]    {result.username}")

    def _refresh_stats(self) -> None:
        self.activity_index = (self.activity_index + 1) % len(ACTIVITY_FRAMES)
        if self.engine is None:
            return
        stats = self.engine.stats
        elapsed = max(0.001, time.monotonic() - self.started_at)
        window = min(5.0, elapsed)
        rate = len(self.result_times) / window if window > 0 else 0.0
        activity = "◇" if self.engine.paused else ACTIVITY_FRAMES[self.activity_index]
        proxy_text = "ON" if self.app.config.proxies.enabled else "OFF"
        text = (
            f"{activity}  {self.current_username}\n"
            f"Checked {stats.checked:,}   Available {stats.available:,}   "
            f"Taken {stats.taken:,}\n"
            f"Invalid {stats.invalid:,}   Errors {stats.network_errors + stats.unknown:,}   "
            f"429 {stats.rate_limited:,}\n"
            f"Rate {rate:.1f}/s   Queue ≤ {self.scanner.queue_size}   "
            f"Proxies {proxy_text}   Runtime {_format_duration(elapsed)}"
        )
        self.query_one("#scan-stats", Static).update(text)


class NymApp(App[None]):
    TITLE = "Nym"
    CSS = """
    Screen {
        background: #0b0c0e;
        color: #e8e6df;
        align: center middle;
        overflow-y: auto;
    }

    #pulse {
        width: 52;
        height: 9;
        content-align: center middle;
        text-align: center;
        color: #d8a657;
    }

    #menu-card, .panel {
        width: 72;
        height: auto;
        max-height: 95%;
        padding: 1 3;
        background: #111318;
    }

    .wide {
        width: 96;
    }

    .scan-panel {
        min-height: 34;
    }

    .brand {
        height: 3;
        content-align: center middle;
        text-align: center;
        color: #e0af68;
        text-style: bold;
    }

    .title {
        height: 2;
        color: #e0af68;
        text-style: bold;
    }

    .muted, .hint {
        color: #777b86;
        margin-bottom: 1;
    }

    .hint {
        text-align: center;
        margin-top: 1;
    }

    .error {
        color: #f7768e;
        min-height: 1;
    }

    Button {
        width: 100%;
        margin: 0 0 1 0;
        background: #171a20;
        color: #e8e6df;
        border: none;
    }

    Button:hover, Button:focus {
        background: #242833;
        color: #e0af68;
    }

    Input, Select {
        margin-bottom: 1;
        background: #0e1014;
        border: tall #242833;
    }

    .row {
        height: auto;
        layout: horizontal;
    }

    .row > Vertical {
        width: 1fr;
        margin-right: 1;
    }

    .row > Input {
        width: 1fr;
        margin-right: 1;
    }

    .row > Button {
        width: auto;
        min-width: 10;
        margin-right: 1;
    }

    .actions {
        height: 3;
        margin-top: 1;
    }

    .actions > Button {
        width: 1fr;
        margin-right: 1;
    }

    RichLog {
        height: 1fr;
        min-height: 12;
        background: #0e1014;
        border: tall #1b1e26;
        padding: 1 2;
        margin-bottom: 1;
    }

    #scan-stats {
        min-height: 7;
        padding: 1 2;
        background: #0e1014;
        color: #c6c8d1;
    }

    #hit-flash {
        height: 2;
        content-align: center middle;
        color: #9ece6a;
        text-style: bold;
    }
    """

    def __init__(self) -> None:
        super().__init__()
        self.paths = AppPaths.discover()
        self.paths.ensure()
        self.config = load_config(self.paths.config)

    def on_mount(self) -> None:
        self.push_screen(MainMenuScreen())
        self.push_screen(SplashScreen(self.config))


def run_tui() -> None:
    NymApp().run()


def _make_proxy_pool(config: AppConfig, paths: AppPaths) -> ProxyPool:
    proxy_path = Path(config.proxies.file)
    if not proxy_path.is_absolute():
        proxy_path = paths.root / proxy_path
    return ProxyPool.from_file(
        proxy_path,
        cooldown_seconds=config.proxies.cooldown_seconds,
        dead_after_failures=config.proxies.dead_after_failures,
        fallback_direct=config.proxies.fallback_direct,
    )


def _validate_scanner(scanner: ScannerConfig) -> None:
    if scanner.mode not in {"random", "sequential", "pattern", "dictionary"}:
        raise ValueError("unsupported scan mode")
    if not 2 <= scanner.length <= 32:
        raise ValueError("length must be between 2 and 32")
    if not scanner.charset:
        raise ValueError("charset must not be empty")
    if scanner.workers < 1:
        raise ValueError("workers must be >= 1")
    if scanner.queue_size < scanner.workers:
        raise ValueError("queue must be >= workers")
    if scanner.interval < 0 or scanner.jitter < 0:
        raise ValueError("interval and jitter must be >= 0")
    if scanner.limit is not None and scanner.limit < 1:
        raise ValueError("limit must be >= 1")


def _format_duration(seconds: float) -> str:
    total = int(seconds)
    minutes, secs = divmod(total, 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours:02d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"
