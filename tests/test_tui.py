import asyncio
from pathlib import Path

import pytest
from textual.widgets import Button

from nym.checker import UsernameChecker
from nym.config import ScannerConfig
from nym.models import CheckResult, CheckStatus
from nym.ui import NymApp, ScanScreen, ScanSetupScreen


@pytest.mark.asyncio
async def test_tui_boots_headless(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    app = NymApp()
    app.config.interface.animations = False

    async with app.run_test() as pilot:
        await pilot.pause()
        assert app.screen is not None


@pytest.mark.asyncio
async def test_scan_setup_start_button_visible_in_small_terminal(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)
    app = NymApp()
    app.config.interface.animations = False

    async with app.run_test(size=(100, 30)) as pilot:
        await app.push_screen(ScanSetupScreen(ScannerConfig()))
        await pilot.pause()
        start = app.screen.query_one("#start", Button)
        assert start.region.height > 0
        assert 0 <= start.region.y < app.size.height


@pytest.mark.asyncio
async def test_scan_screen_actually_runs_engine(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.chdir(tmp_path)

    async def fake_check(self: UsernameChecker, username: str) -> CheckResult:
        return CheckResult(username, CheckStatus.TAKEN, http_status=200)

    monkeypatch.setattr(UsernameChecker, "check", fake_check)

    app = NymApp()
    app.config.interface.animations = False
    scanner = ScannerConfig(
        mode="sequential",
        length=2,
        charset="a",
        workers=1,
        queue_size=2,
        interval=0,
        limit=1,
    )

    async with app.run_test():
        screen = ScanScreen(scanner)
        await app.push_screen(screen)

        for _ in range(40):
            await asyncio.sleep(0.05)
            if screen.engine is not None and screen.engine.stats.checked == 1:
                break

        assert screen.engine is not None
        assert screen.engine.stats.checked == 1
        assert screen.current_username == "aa"
