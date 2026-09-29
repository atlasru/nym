from __future__ import annotations

import asyncio
import copy
import time
from collections import deque

import httpx
from textual.app import App, ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.css.query import NoMatches
from textual.screen import Screen
from textual.widgets import Button, Input, Label, RichLog, Select, Static

from . import tui as legacy
from .checker import UsernameChecker
from .config import AppPaths, ScannerConfig, load_config, save_config
from .engine import ScanEngine
from .models import CheckResult, CheckStatus
from .proxy import ProxyPool, ProxyState
from .runtime import build_usernames
from .scheduler import RateScheduler
from .storage import Storage


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
        button_id = event.button.id
        if button_id == "new-scan":
            app.push_screen(ScanSetupScreen(copy.deepcopy(app.config.scanner)))
        elif button_id == "resume":
            app.push_screen(ScanScreen(copy.deepcopy(app.config.scanner), resume=True))
        elif button_id == "results":
            app.push_screen(legacy.ResultsScreen(app.paths.available))
        elif button_id == "configuration":
            app.push_screen(legacy.ConfigurationScreen())
        elif button_id == "proxies":
            app.push_screen(legacy.ProxyScreen())
        elif button_id == "exit":
            app.exit()


class BackScreen(Screen):
    BINDINGS = [("escape", "back", "Back")]

    def action_back(self) -> None:
        self.app.pop_screen()


class ScanSetupScreen(BackScreen):
    BINDINGS = BackScreen.BINDINGS + [("ctrl+enter", "start_scan", "Start scan")]

    def __init__(self, scanner: ScannerConfig) -> None:
        super().__init__()
        self.scanner = scanner

    def compose(self) -> ComposeResult:
        with Vertical(classes="panel setup-panel"):
            yield Static("✦  New scan", classes="title")
            yield Static("Configure the scan, then press Start scan.", classes="muted")
            with VerticalScroll(id="scan-form"):
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
                with Horizontal(classes="row"):
                    with Vertical():
                        yield Label("Length · 2–32")
                        yield Input(str(self.scanner.length), id="length")
                    with Vertical():
                        yield Label("Stop after N · empty = unlimited")
                        yield Input(
                            ""
                            if self.scanner.limit is None
                            else str(self.scanner.limit),
                            id="limit",
                        )
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
                yield Static("", id="form-error", classes="error")
            with Horizontal(classes="actions fixed-actions"):
                yield Button("Start scan", id="start", variant="primary")
                yield Button("Back", id="back")

    def on_mount(self) -> None:
        self.query_one("#start", Button).focus()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "back":
            self.app.pop_screen()
        elif event.button.id == "start":
            self.action_start_scan()

    def action_start_scan(self) -> None:
        try:
            scanner = self._read_scanner()
        except (TypeError, ValueError) as exc:
            self.query_one("#form-error", Static).update(f"× {exc}")
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
        legacy._validate_scanner(scanner)
        return scanner


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
        self.scan_worker = None
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
        self.query_one("#scan-status", Static).update("◌ Initializing scanner…")
        self.query_one("#scan-log", RichLog).write("[dim]Initializing scan…[/]")
        self.scan_worker = self.run_worker(
            self._run_scan(),
            name="nym-scan",
            group="scan",
            exclusive=True,
        )

    def on_unmount(self) -> None:
        if self.engine is not None:
            self.engine.stop()
        if self.scan_worker is not None:
            self.scan_worker.cancel()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "pause":
            self.action_pause_resume()
        elif event.button.id == "stop":
            self._stop_scan()
        elif event.button.id == "back" and self.finished:
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
        was_cancelled = False

        try:
            self.query_one("#scan-status", Static).update("◌ Preparing generator…")
            usernames = build_usernames(self.scanner, self.app.paths.root)
            await asyncio.sleep(0)

            if self.app.config.proxies.enabled:
                self.query_one("#scan-status", Static).update("◔ Testing proxies…")
                proxy_pool = legacy._make_proxy_pool(self.app.config, self.app.paths)
                if not proxy_pool.endpoints:
                    if proxy_pool.fallback_direct:
                        log.write("[yellow]No valid proxies; using direct fallback.[/]")
                    else:
                        raise RuntimeError(
                            "proxy routing is ON, but proxies.txt has no valid proxies"
                        )
                else:
                    await proxy_pool.open()
                    log.write(
                        f"[dim]Proxy pool: {len(proxy_pool.endpoints)} loaded[/]"
                    )
                    await proxy_pool.health_check_all(
                        concurrency=min(8, len(proxy_pool.endpoints))
                    )
                    healthy = [
                        endpoint
                        for endpoint in proxy_pool.endpoints
                        if endpoint.state is ProxyState.READY
                    ]
                    failed = [
                        endpoint
                        for endpoint in proxy_pool.endpoints
                        if endpoint.state is not ProxyState.READY
                    ]
                    log.write(
                        "[dim]Proxy preflight: "
                        f"{len(healthy)} ready · {len(failed)} failed[/]"
                    )
                    for endpoint in failed[:10]:
                        detail = endpoint.last_error or endpoint.state.value
                        log.write(f"[red]× proxy[/] {endpoint.display} · {detail}")
                    if not healthy and not proxy_pool.fallback_direct:
                        raise RuntimeError("no proxy passed Discord preflight")
            else:
                log.write("[dim]Direct connection · proxy routing OFF[/]")

            self.query_one("#scan-status", Static).update("◕ Opening storage…")
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
                    label = "Starting last config" if self.resume else "Scanning"
                    self.query_one("#scan-status", Static).update(f"✦ {label}")
                    log.write(
                        "[dim]"
                        f"{self.scanner.mode} · length {self.scanner.length} · "
                        f"{self.scanner.workers} workers · "
                        f"{self.scanner.interval:.3f}s interval"
                        "[/]"
                    )
                    async for result in self.engine.run(usernames):
                        self._record_result(result, log)
                        if (
                            self.scanner.limit is not None
                            and self.engine.stats.checked >= self.scanner.limit
                        ):
                            self.engine.stop()
                            break
        except asyncio.CancelledError:
            was_cancelled = True
        except Exception as exc:
            had_error = True
            if self.is_mounted:
                detail = f"{type(exc).__name__}: {exc}"
                log.write(f"[red]× {detail}[/]")
                self.query_one("#scan-status", Static).update("× Scan failed")
        finally:
            if proxy_pool is not None:
                await proxy_pool.close()
            self.finished = True
            if self.is_mounted:
                try:
                    if not had_error and not was_cancelled:
                        self.query_one("#scan-status", Static).update("✓ Finished")
                    self.query_one("#pause", Button).disabled = True
                    self.query_one("#stop", Button).disabled = True
                    self.query_one("#back", Button).disabled = False
                except NoMatches:
                    pass

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
            retry = ""
            if result.retry_after is not None:
                retry = f" · retry after {result.retry_after:.1f}s"
            log.write(f"[yellow]◇ rate limit[/] {result.username}{retry}")
        elif result.status is CheckStatus.NETWORK_ERROR:
            detail = f" · {result.error}" if result.error else ""
            log.write(f"[red]× network[/]    {result.username}{detail}")
        elif result.status is CheckStatus.INVALID:
            log.write(f"[yellow]× invalid[/]    {result.username}")
        else:
            detail = f" · {result.error}" if result.error else ""
            log.write(f"[red]× unknown[/]    {result.username}{detail}")

    def _refresh_stats(self) -> None:
        self.activity_index = (self.activity_index + 1) % len(legacy.ACTIVITY_FRAMES)
        if self.engine is None:
            return
        stats = self.engine.stats
        now = time.monotonic()
        elapsed = max(0.001, now - self.started_at)
        while self.result_times and now - self.result_times[0] > 5.0:
            self.result_times.popleft()
        window = min(5.0, elapsed)
        rate = len(self.result_times) / window if window > 0 else 0.0
        activity = (
            "◇" if self.engine.paused else legacy.ACTIVITY_FRAMES[self.activity_index]
        )
        proxy_text = "ON" if self.app.config.proxies.enabled else "OFF"
        retry_remaining = self.engine.rate_limit_remaining
        if retry_remaining > 0 and not self.engine.paused and not self.finished:
            self.query_one("#scan-status", Static).update(
                f"◇ Rate limited · retry in {retry_remaining:.1f}s"
            )
        text = (
            f"{activity}  {self.current_username}\n"
            f"Checked {stats.checked:,}   Available {stats.available:,}   "
            f"Taken {stats.taken:,}\n"
            f"Invalid {stats.invalid:,}   "
            f"Errors {stats.network_errors + stats.unknown:,}   "
            f"429 {self.engine.rate_limit_events:,}\n"
            f"Rate {rate:.1f}/s   Queue ≤ {self.scanner.queue_size}   "
            f"Proxies {proxy_text}   Runtime {legacy._format_duration(elapsed)}"
        )
        self.query_one("#scan-stats", Static).update(text)


class NymApp(App[None]):
    TITLE = "Nym"
    CSS = legacy.NymApp.CSS + """
    .setup-panel { height: 94%; max-height: 94%; }
    #scan-form { height: 1fr; padding-right: 1; }
    .fixed-actions { height: 3; min-height: 3; }
    """

    def __init__(self) -> None:
        super().__init__()
        self.paths = AppPaths.discover()
        self.paths.ensure()
        self.config = load_config(self.paths.config)

    def on_mount(self) -> None:
        self.push_screen(MainMenuScreen())
        self.push_screen(legacy.SplashScreen(self.config))


def run_tui() -> None:
    NymApp().run()
