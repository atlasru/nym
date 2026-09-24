# Nym

Local-first terminal username availability scanner.

Nym is currently in early development. The v1 target is Windows x64 with an
async scanning engine, persistent results, proxy support, and a terminal UI.

## Development

Requires Python 3.13+.

```powershell
python -m pip install -e ".[dev]"
python -m pytest
python -m ruff check .
```

Current milestone: **M1 — engine**.

## License

MIT.
