from __future__ import annotations

import sys
from pathlib import Path


if sys.version_info < (3, 13):
    version = ".".join(map(str, sys.version_info[:3]))
    raise SystemExit(
        f"Nym requires Python 3.13 or newer; found {version}.\\n"
        "Run with: py -3.13 nym_entry.py"
    )


source_dir = Path(__file__).resolve().parent / "src"
if source_dir.is_dir() and str(source_dir) not in sys.path:
    sys.path.insert(0, str(source_dir))

from nym.cli import main  # noqa: E402, I001


if __name__ == "__main__":
    main()
