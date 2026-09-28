# Nym

Fast, local username availability scanner with a terminal-first interface.

Nym v0.2 focuses on Windows x64 and now includes the first usable TUI: startup pulse,
scan configuration, live dashboard, persistent results, and proxy management.

## Run

Launch `nym.exe` with no arguments to open the TUI.

CLI mode is still available:

```powershell
nym.exe scan --mode random --length 4 --limit 100
```

Portable data is stored beside the executable:

- `config.toml`
- `proxies.txt`
- `available.txt`
- `data/nym.db`

Proxy lines may use HTTP, HTTPS, or SOCKS5 with optional authentication. Nym respects
server-provided retry delays and does not use proxies to bypass rate limits.

## Development

Requires Python 3.13+.

```powershell
python -m pip install -e ".[dev]"
python -m pytest
python -m ruff check .
```

## License

MIT.
