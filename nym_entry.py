from __future__ import annotations

import sys
from pathlib import Path


source_dir = Path(__file__).resolve().parent / "src"
if source_dir.is_dir() and str(source_dir) not in sys.path:
    sys.path.insert(0, str(source_dir))

from nym.cli import main  # noqa: E402, I001


if __name__ == "__main__":
    main()
