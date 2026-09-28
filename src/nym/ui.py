from __future__ import annotations

import copy
import time

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, Input, Label, Select, Static

from . import tui as legacy
from .config import ScannerConfig, save_config


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
        self.query_one("#scan-log").write("[dim]Initializing scan…[/]")
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
