from __future__ import annotations

import argparse
import asyncio
import sys

import httpx

from .checker import UsernameChecker
from .config import AppPaths, ScannerConfig, load_config
from .engine import ScanEngine
from .generator import DEFAULT_CHARSET
from .proxy import ProxyPool
from .runtime import build_usernames
from .scheduler import RateScheduler
from .storage import Storage


async def _run_scan(args: argparse.Namespace) -> int:
    paths = AppPaths.discover()
    paths.ensure()
    config = load_config(paths.config)
    scanner = ScannerConfig(
        mode=args.mode,
        length=args.length,
        charset=args.charset,
        pattern=args.pattern,
        dictionary_file=args.dictionary,
        workers=args.workers,
        queue_size=args.queue_size,
        interval=args.interval,
        jitter=args.jitter,
        seed=args.seed,
        limit=args.limit,
    )
    names = build_usernames(scanner, paths.root)

    proxy_pool: ProxyPool | None = None
    if config.proxies.enabled:
        proxy_path = paths.root / config.proxies.file
        proxy_pool = ProxyPool.from_file(
            proxy_path,
            cooldown_seconds=config.proxies.cooldown_seconds,
            dead_after_failures=config.proxies.dead_after_failures,
            fallback_direct=config.proxies.fallback_direct,
        )
        if proxy_pool.endpoints:
            await proxy_pool.open()
        elif not proxy_pool.fallback_direct:
            raise RuntimeError(
                "proxy routing is enabled but proxies.txt has no valid proxies"
            )

    try:
        async with httpx.AsyncClient() as client:
            checker = UsernameChecker(client, proxy_pool=proxy_pool)
            scheduler = RateScheduler(scanner.interval, jitter=scanner.jitter, seed=scanner.seed)
            async with Storage(paths.database, available_file=paths.available) as storage:
                engine = ScanEngine(
                    checker,
                    storage,
                    scheduler,
                    workers=scanner.workers,
                    queue_size=scanner.queue_size,
                )
                async for result in engine.run(names):
                    print(f"{result.status.value:13} {result.username}")
                    if scanner.limit is not None and engine.stats.checked >= scanner.limit:
                        engine.stop()
                        break
    finally:
        if proxy_pool is not None:
            await proxy_pool.close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="nym")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("tui", help="open the terminal interface")
    subparsers.add_parser("config", help="print the config file path")

    scan = subparsers.add_parser("scan", help="run a scan without the TUI")
    scan.add_argument(
        "--mode",
        choices=("random", "sequential", "pattern", "dictionary"),
        default="random",
    )
    scan.add_argument("--length", type=int, default=4)
    scan.add_argument("--charset", default=DEFAULT_CHARSET)
    scan.add_argument("--pattern", default="@@@#")
    scan.add_argument("--dictionary", default="dictionaries/words.txt")
    scan.add_argument("--seed", type=int)
    scan.add_argument("--workers", type=int, default=4)
    scan.add_argument("--queue-size", type=int, default=512)
    scan.add_argument("--interval", type=float, default=0.1)
    scan.add_argument("--jitter", type=float, default=0.0)
    scan.add_argument("--limit", type=int)
    return parser


def main(argv: list[str] | None = None) -> None:
    raw_args = list(sys.argv[1:] if argv is None else argv)
    if not raw_args:
        from .ui import run_tui

        run_tui()
        return

    if raw_args[0].startswith("-"):
        raw_args.insert(0, "scan")

    args = build_parser().parse_args(raw_args)
    if args.command in {None, "tui"}:
        from .ui import run_tui

        run_tui()
        return
    if args.command == "config":
        paths = AppPaths.discover()
        paths.ensure()
        load_config(paths.config)
        print(paths.config)
        return
    raise SystemExit(asyncio.run(_run_scan(args)))


if __name__ == "__main__":
    main()
