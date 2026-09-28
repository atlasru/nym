from __future__ import annotations

from collections.abc import Iterable, Iterator
from pathlib import Path

from .config import ScannerConfig
from .generator import dictionary, pattern, random_names, sequential


def build_usernames(config: ScannerConfig, root: Path) -> Iterable[str]:
    if config.mode == "sequential":
        return sequential(config.length, config.charset)
    if config.mode == "pattern":
        return pattern(config.pattern, custom=config.charset)
    if config.mode == "dictionary":
        dictionary_path = Path(config.dictionary_file)
        if not dictionary_path.is_absolute():
            dictionary_path = root / dictionary_path
        return _dictionary_file(dictionary_path)
    return random_names(config.length, config.charset, seed=config.seed)


def _dictionary_file(path: Path) -> Iterator[str]:
    with path.open("r", encoding="utf-8") as handle:
        yield from dictionary(handle)
