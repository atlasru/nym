from __future__ import annotations

import json
import sys
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .generator import DEFAULT_CHARSET


@dataclass(slots=True)
class ScannerConfig:
    mode: str = "random"
    length: int = 4
    charset: str = DEFAULT_CHARSET
    pattern: str = "@@@#"
    dictionary_file: str = "dictionaries/words.txt"
    workers: int = 4
    queue_size: int = 512
    interval: float = 0.1
    jitter: float = 0.0
    seed: int | None = None
    limit: int | None = None


@dataclass(slots=True)
class ProxyConfig:
    enabled: bool = False
    file: str = "proxies.txt"
    fallback_direct: bool = False
    cooldown_seconds: float = 30.0
    dead_after_failures: int = 6


@dataclass(slots=True)
class InterfaceConfig:
    animations: bool = True
    animation_speed: float = 1.0
    reduced_motion: bool = False
    ascii_logo: bool = True
    compact: bool = False


@dataclass(slots=True)
class AppConfig:
    scanner: ScannerConfig = field(default_factory=ScannerConfig)
    proxies: ProxyConfig = field(default_factory=ProxyConfig)
    interface: InterfaceConfig = field(default_factory=InterfaceConfig)


@dataclass(slots=True, frozen=True)
class AppPaths:
    root: Path
    config: Path
    database: Path
    available: Path
    proxies: Path
    exports: Path
    logs: Path

    @classmethod
    def discover(cls) -> AppPaths:
        if getattr(sys, "frozen", False):
            root = Path(sys.executable).resolve().parent
        else:
            root = Path.cwd()
        return cls(
            root=root,
            config=root / "config.toml",
            database=root / "data" / "nym.db",
            available=root / "available.txt",
            proxies=root / "proxies.txt",
            exports=root / "data" / "exports",
            logs=root / "data" / "logs",
        )

    def ensure(self) -> None:
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self.exports.mkdir(parents=True, exist_ok=True)
        self.logs.mkdir(parents=True, exist_ok=True)
        self.proxies.touch(exist_ok=True)


_VALID_MODES = {"random", "sequential", "pattern", "dictionary"}


def load_config(path: Path) -> AppConfig:
    if not path.exists():
        config = AppConfig()
        save_config(path, config)
        return config

    with path.open("rb") as handle:
        raw = tomllib.load(handle)

    scanner_raw = raw.get("scanner", {})
    proxy_raw = raw.get("proxies", {})
    interface_raw = raw.get("interface", {})

    scanner = ScannerConfig(
        mode=str(scanner_raw.get("mode", "random")),
        length=int(scanner_raw.get("length", 4)),
        charset=str(scanner_raw.get("charset", DEFAULT_CHARSET)),
        pattern=str(scanner_raw.get("pattern", "@@@#")),
        dictionary_file=str(scanner_raw.get("dictionary_file", "dictionaries/words.txt")),
        workers=int(scanner_raw.get("workers", 4)),
        queue_size=int(scanner_raw.get("queue_size", 512)),
        interval=float(scanner_raw.get("interval", 0.1)),
        jitter=float(scanner_raw.get("jitter", 0.0)),
        seed=_optional_int(scanner_raw.get("seed")),
        limit=_optional_int(scanner_raw.get("limit")),
    )
    proxies = ProxyConfig(
        enabled=bool(proxy_raw.get("enabled", False)),
        file=str(proxy_raw.get("file", "proxies.txt")),
        fallback_direct=bool(proxy_raw.get("fallback_direct", False)),
        cooldown_seconds=float(proxy_raw.get("cooldown_seconds", 30.0)),
        dead_after_failures=int(proxy_raw.get("dead_after_failures", 6)),
    )
    interface = InterfaceConfig(
        animations=bool(interface_raw.get("animations", True)),
        animation_speed=float(interface_raw.get("animation_speed", 1.0)),
        reduced_motion=bool(interface_raw.get("reduced_motion", False)),
        ascii_logo=bool(interface_raw.get("ascii_logo", True)),
        compact=bool(interface_raw.get("compact", False)),
    )
    config = AppConfig(scanner=scanner, proxies=proxies, interface=interface)
    validate_config(config)
    return config


def save_config(path: Path, config: AppConfig) -> None:
    validate_config(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "\n".join(
        [
            "[scanner]",
            f"mode = {_toml_string(config.scanner.mode)}",
            f"length = {config.scanner.length}",
            f"charset = {_toml_string(config.scanner.charset)}",
            f"pattern = {_toml_string(config.scanner.pattern)}",
            f"dictionary_file = {_toml_string(config.scanner.dictionary_file)}",
            f"workers = {config.scanner.workers}",
            f"queue_size = {config.scanner.queue_size}",
            f"interval = {config.scanner.interval}",
            f"jitter = {config.scanner.jitter}",
            f"seed = {_toml_optional_int(config.scanner.seed)}",
            f"limit = {_toml_optional_int(config.scanner.limit)}",
            "",
            "[proxies]",
            f"enabled = {_toml_bool(config.proxies.enabled)}",
            f"file = {_toml_string(config.proxies.file)}",
            f"fallback_direct = {_toml_bool(config.proxies.fallback_direct)}",
            f"cooldown_seconds = {config.proxies.cooldown_seconds}",
            f"dead_after_failures = {config.proxies.dead_after_failures}",
            "",
            "[interface]",
            f"animations = {_toml_bool(config.interface.animations)}",
            f"animation_speed = {config.interface.animation_speed}",
            f"reduced_motion = {_toml_bool(config.interface.reduced_motion)}",
            f"ascii_logo = {_toml_bool(config.interface.ascii_logo)}",
            f"compact = {_toml_bool(config.interface.compact)}",
            "",
        ]
    )
    path.write_text(text, encoding="utf-8")


def validate_config(config: AppConfig) -> None:
    scanner = config.scanner
    if scanner.mode not in _VALID_MODES:
        raise ValueError(f"unsupported scanner mode: {scanner.mode}")
    if not 2 <= scanner.length <= 32:
        raise ValueError("username length must be between 2 and 32")
    if not scanner.charset:
        raise ValueError("charset must not be empty")
    if scanner.workers < 1:
        raise ValueError("workers must be >= 1")
    if scanner.queue_size < scanner.workers:
        raise ValueError("queue_size must be >= workers")
    if scanner.interval < 0 or scanner.jitter < 0:
        raise ValueError("interval and jitter must be >= 0")
    if scanner.limit is not None and scanner.limit < 1:
        raise ValueError("limit must be >= 1 when set")
    if config.proxies.cooldown_seconds < 0:
        raise ValueError("proxy cooldown_seconds must be >= 0")
    if config.proxies.dead_after_failures < 1:
        raise ValueError("proxy dead_after_failures must be >= 1")
    if config.interface.animation_speed <= 0:
        raise ValueError("animation_speed must be > 0")


def _optional_int(value: object) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _toml_optional_int(value: int | None) -> str:
    return "\"\"" if value is None else str(value)


def _toml_bool(value: bool) -> str:
    return "true" if value else "false"


def _toml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)
