from pathlib import Path

from nym.config import AppConfig, load_config, save_config


def test_config_round_trip(tmp_path: Path) -> None:
    path = tmp_path / "config.toml"
    config = AppConfig()
    config.scanner.mode = "pattern"
    config.scanner.pattern = "@@##"
    config.scanner.limit = 25
    config.proxies.enabled = True
    config.interface.animation_speed = 1.5

    save_config(path, config)
    loaded = load_config(path)

    assert loaded.scanner.mode == "pattern"
    assert loaded.scanner.pattern == "@@##"
    assert loaded.scanner.limit == 25
    assert loaded.proxies.enabled is True
    assert loaded.interface.animation_speed == 1.5


def test_missing_config_is_created(tmp_path: Path) -> None:
    path = tmp_path / "config.toml"
    loaded = load_config(path)

    assert path.exists()
    assert loaded.scanner.length == 4
