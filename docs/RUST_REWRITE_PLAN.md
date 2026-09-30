# Nym Rust rewrite plan

This branch rewrites Nym from Python/Textual to Rust without adding product features until feature parity is reached.

## Goals

- Keep `main` on the known-good Python implementation until the Rust rewrite is validated.
- Preserve current config defaults and data paths where practical.
- Preserve scanner semantics: generation modes, bounded queue, storage, proxy preflight, result statuses, pause/stop, and rate-limit handling.
- Replace the terminal UI with a native pseudo-TUI desktop window.
- Make engine state observable through events so UI code never owns scanner policy.

## Stack

- Rust 2024
- Tokio for async runtime and task orchestration
- reqwest for HTTP
- Slint for the native desktop UI
- serde + toml for configuration
- rusqlite for local persistence
- tracing for structured diagnostics

## Architecture

```text
Slint UI
  ^ events / snapshots
  |
App controller
  |
  +-- Scan engine
  |    +-- Generator
  |    +-- Worker pool
  |    +-- Rate scheduler
  |    +-- Result sink
  |
  +-- Proxy pool
  |    +-- health state
  |    +-- leases
  |    +-- cooldown metadata
  |
  +-- Storage
```

The UI renders state only. It does not choose proxies, retry requests, or change rate-limit policy.

## Migration phases

1. **Foundation**
   - Rust workspace/app builds on Windows.
   - Slint window opens without a console host.
   - Existing TOML config can be loaded and validated.
   - Core status/result types and app paths exist.

2. **Parity core**
   - Port random/sequential/pattern/dictionary generators.
   - Port SQLite result persistence and `available.txt` append.
   - Port checker response classification.
   - Port bounded producer/worker pipeline and pause/stop.
   - Reproduce current behavior with mock HTTP tests.

3. **Proxy parity**
   - Port proxy parsing/redaction and health checks.
   - Add explicit proxy leases and observable states.
   - Preserve server `Retry-After`; global limits pause the scanner.
   - Do not add anti-bot or rate-limit bypass behavior.

4. **Native dashboard parity**
   - Pseudo-TUI dashboard in Slint.
   - Top stats, worker log, proxy state panel, hit feed.
   - Keyboard navigation and compact layout.
   - Visual stage exists as a replaceable asset surface, but no new art feature is required for parity.

5. **Validation**
   - Windows CI: format, clippy, tests, release build, artifact upload.
   - Compare Python/Rust results on deterministic generators and mock HTTP fixtures.
   - Manual Windows smoke test.

6. **Only after parity**
   - New proxy/worker scheduling design.
   - Webhook notifications.
   - Visual packs / animated pixel art.
   - Any other new feature.

## Merge gate

Do not merge this branch to `main` until the Rust build is green in CI and manual Windows testing confirms feature parity for the current Python reference implementation.
