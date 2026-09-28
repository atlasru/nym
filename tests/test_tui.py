from pathlib import Path

import pytest

from nym.tui import NymApp


@pytest.mark.asyncio
async def test_tui_boots_headless(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    app = NymApp()
    app.config.interface.animations = False

    async with app.run_test() as pilot:
        await pilot.pause()
        assert app.screen is not None
