from __future__ import annotations

import argparse
import asyncio
from pathlib import Path

import httpx

from .checker import UsernameChecker
from .engine import ScanEngine
from .generator import DEFAULT_CHARSET, random_names, sequential
from .scheduler import RateScheduler
from .storage import Storage


async def _run(args: argparse.Namespace) -> int:
    if args.mode == "sequential":
        names = sequential(args.length, args.charset)
    else:
        names = random_names(args.length, args.charset, seed=args.seed)

    database = Path(args.data_dir) / "nym.db"
    available = Path(args.data_dir).parent / "available.txt"

    async with httpx.AsyncClient() as client:
        checker = UsernameChecker(client)
        scheduler = RateScheduler(args.interval, jitter=args.jitter)
        async with Storage(database, available_file=available) as storage:
            engine = ScanEngine(
                checker,
                storage,
                scheduler,
                workers=args.workers,
                queue_size=args.queue_size,
            )

            count = 0
            async for result in engine.run(names):
                print(f"{result.status.value:13} {result.username}")
                count += 1
                if args.limit is not None and count >= args.limit:
                    engine.stop()
                    break

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="nym")
    parser.add_argument("--mode", choices=("random", "sequential"), default="random")
    parser.add_argument("--length", type=int, default=4)
    parser.add_argument("--charset", default=DEFAULT_CHARSET)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--queue-size", type=int, default=512)
    parser.add_argument("--interval", type=float, default=0.1)
    parser.add_argument("--jitter", type=float, default=0.0)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--data-dir", default="data")
    return parser


def main() -> None:
    raise SystemExit(asyncio.run(_run(build_parser().parse_args())))


if __name__ == "__main__":
    main()
