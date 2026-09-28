from __future__ import annotations

import asyncio
import copy
import time

import httpx
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, Input, Label, RichLog, Select, Static

from . import tui as legacy
from .checker import UsernameChecker
from .config import ScannerConfig, save_config
from .engine import ScanEngine
from .proxy import ProxyPool
from .runtime import build_usernames
from .scheduler import RateScheduler
from .storage import Storage


class ScanSetupScreen(legacy.ScanSetupScreen):
    """Scan setup with a permanently visible action bar."""

    BINDINGS = legacy.BackScreen.BINDINGS + [("ctrl+enter", "start_scan", "Start scan")]

    def compose(self) -> ComposeResult:
        with Vertical(classes="panel setup-panel"):
            yield Static("✦  New scan", classes="title")
            yield Static(
                "Configure the scan, then press Start scan. Ctrl+Enter also starts.",
                classes="muted",
            )
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
                            "" if self.scanner.limit is None else str(self.scanner.limit),
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
            return
        if event.button.id == "start":
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


class ScanScreen(legacy.ScanScreen):
    """Scan dashboard driven by Textual's worker lifecycle."""

    def __init__(self, scanner: ScannerConfig, *, resume: bool = False) -> None:
        super().__init__(scanner, resume=resume)
        self.scan_worker = None

    def on_mount(self) -> None:
        self.started_at = time.monotonic()
        self.set_interval(0.2, self._refresh_stats)
        self.query_one("#scan-status", Static).update("◌ Initializing scanner…")
        self.query_one("#scan-log", RichLog).write("[dim]Initializing scan…[/]")
        self.scan_worker = self.run_worker(
            self._run_scan_fixed(),
            name="nym-scan",
            group="scan",
            exclusive=True,
        )

    def on_unmount(self) -> None:
        if self.engine is not None:
            self.engine.stop()
        if self.scan_worker is not None:
            self.scan_worker.cancel()

    async def _run_scan_fixed(self) -> None:
        log = self.query_one("#scan-log", RichLog)
        proxy_pool: ProxyPool | None = None
        had_error = False
        was_cancelled = False

        try:
            self.query_one("#scan-status", Static).update("◌ Preparing generator…")
            usernames = build_usernames(self.scanner, self.app.paths.root)
            await asyncio.sleep(0)

            if self.app.config.proxies.enabled:
                self.query_one("#scan-status", Static).update("◔ Loading proxies…")
                proxy_pool = legacy._make_proxy_pool(self.app.config, self.app.paths)
                if proxy_pool.endpoints:
                    await proxy_pool.open()
                    log.write(f"[dim]Proxy pool: {len(proxy_pool.endpoints)} loaded[/]")
                elif not proxy_pool.fallback_direct:
                    raise RuntimeError(
                        "proxy routing is ON, but proxies.txt has no valid proxies"
                    )
                else:
                    log.write("[yellow]No valid proxies; using direct fallback.[/]")
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
                if not had_error and not was_cancelled:
                    self.query_one("#scan-status", Static).update("✓ Finished")
                self.query_one("#pause", Button).disabled = True
                self.query_one("#stop", Button).disabled = True
                self.query_one("#back", Button).disabled = False


class MainMenuScreen(legacy.MainMenuScreen):
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


class NymApp(legacy.NymApp):
    CSS = (
        legacy.NymApp.CSS
        + """
        .setup-panel {
            height: 94%;
            max-height: 94%;
        }

        #scan-form {
            height: 1fr;
            padding-right: 1;
        }

        .fixed-actions {
            height: 3;
            min-height: 3;
        }
        """
    )

    def on_mount(self) -> None:
        self.push_screen(MainMenuScreen())
        self.push_screen(legacy.SplashScreen(self.config))


def run_tui() -> None:
    NymApp().run()
