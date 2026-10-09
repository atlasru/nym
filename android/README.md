# Nym Mobile 0.1.1

Native Android port of [atlasru/nym](https://github.com/atlasru/nym), Bugfix based on the latest Android implementation
`d80d155aef2d4cff32c628a54f3f590f84025a1c`. Android 10–16 (API 29–36), ARM64 primary.
Kotlin, Compose, Material 3, coroutines/Flow, OkHttp, SQLite WAL, DataStore.
No Python runtime or WebView. Existing Windows/Python sources are unchanged.

## Install

Download `Nym_Mobile_0.1.1_arm64-debug.apk` from the
[debug prerelease](https://github.com/atlasru/nym/releases/tag/nym-mobile-v0.1.1-debug)
or the **Nym Mobile** Actions artifact. Verify `SHA256SUMS.txt`. Allow installation
from the app opening the APK, then install. Package: `dev.atlas.nym`.

The engine and UI are Kotlin/JVM bytecode. Jetpack's small native support libraries
are packaged for ARM64 and x86_64; the same APK runs on the test emulators.
ARM64 needs no translation. Other native ABIs are not packaged.

The debug build uses the repository's **public development key**, so subsequent
debug builds install as updates. That key is not a production signing identity.
Release signing requires your private keystore, alias and passwords supplied
outside Git, followed by configuring a release signingConfig. No release key is
invented or included. Do not publish this debug build as a production-store APK.

## Use

1. Open **Settings → Generator**. Choose Sequential, Random, Pattern or Dictionary.
2. Set length, alphabet, seed, check limit and interval. Pattern: `@` letters,
   `#` digits, `*` custom alphabet, other valid characters literal.
3. Import a UTF-8 dictionary through Android's document picker or paste names.
   Dictionary entries are lowercased, trimmed, validated and deduplicated.
4. Configure Network, Proxies and Performance as needed. Valid defaults save
   automatically; **Save** reports validation errors explicitly.
5. **Home → Start checking**. Grant notification permission for background
   controls. Pause, stop and save checkpoint are available in Home. The running
   notification offers Pause and Stop; a paused notification offers Resume.
6. Results default to available usernames. Search, filter, sort, tap for details,
   long-press/copy, or export the current filter to CSV with the document picker.
7. Sessions keeps history and resumes incomplete sessions with their original
   configuration. Changing defaults never mutates a saved session.

Availability is a timestamped response, not a reservation or username claim.
No Discord account token, username mutation, CAPTCHA solving or authentication
bypass is included.

## Networking and rate limits

The port preserves the desktop endpoint:
`POST https://discord.com/api/v9/unique-username/username-attempt-unauthed` with
`{"username":"candidate"}`. Only boolean `taken` responses confirm availability.
Malformed JSON, access denial, non-username validation errors and unexpected
responses remain unknown. Explicit username errors are invalid. Access-denial
and CAPTCHA responses stop the session for user attention.

Defaults: one worker, 1500 ms aggregate interval and up to 250 ms added jitter.
Configurable concurrency is bounded to 1–4, interval to 250–300000 ms and retries
to 0–3. One real response warms up the route before concurrent requests begin.
All workers and proxies share the same aggregate cadence. Network/5xx retries
use bounded exponential backoff. Redirects and automatic OkHttp retries are off.
Offline execution waits for a validated system network rather than consuming
candidates. Pause cancels active requests promptly.

**Any HTTP 429 pauses checking on every mobile route.** Session lifecycle and
network status are separate. Stop remains available while paused/rate limited,
including a 30-minute Retry-After, and saves a STOPPED checkpoint immediately. The maximum server Retry-After
(numeric or HTTP date), JSON retry_after and reset-after is honored; absent or
malformed delays default to 60 seconds. Cooldown is persisted across process
restart, new sessions, proxy changes and history deletion. All checking routes observe it. Proxy connectivity tests use example.com,
independent of Discord, and remain accessible during cooldown. Resume is manual after expiration. The pending candidate
is retained. Proxy rotation is never used to avoid a service limit.

## Proxies and an active VPN

Supported URL forms:

```text
proxy.example:8080
proxy.example:8080:user:password
http://user:password@proxy.example:8080
https://user:password@proxy.example:443
socks5://user:password@proxy.example:1080
```

Percent-encode reserved characters in URL credentials. HTTP proxies use CONNECT
for the HTTPS destination. HTTPS proxies add verified TLS to the proxy itself
before CONNECT, with independently verified TLS to Discord. SOCKS5 supports
per-socket username/password authentication and proxy-side target DNS; it does
not install a JVM-global authenticator. IPv6 proxy hosts use brackets. Proxy
leases are bounded; successful routes rotate fairly, failing routes back off.
Direct fallback is explicit and disabled by default. Proxy credentials and
session configuration are encrypted with an Android Keystore AES-GCM key.
Backups are disabled because that key is not portable. Diagnostics redact auth.

**Application proxy settings do not automatically bypass an Android VPN.**
Nym opens ordinary sockets through Android's system network; it neither starts
a second VpnService nor binds to an alternate network. Home shows the observed
default network capabilities and the active application connection route.
That indication does not prove a particular packet's complete physical path.

To leave your phone's VPN enabled but exclude Nym, open the VPN application's
split-tunneling/per-app routing settings, add **Nym (`dev.atlas.nym`)** to its
excluded/bypass list, then reconnect that VPN. The VPN must support exclusions.
Nym cannot modify another VPN's allow/disallow list. If Android's **Always-on
VPN → Block connections without VPN** is enabled, excluded apps may be blocked.
See [Android per-app VPN documentation](https://developer.android.com/develop/connectivity/vpn).

## Checkpoints and background lifecycle

Before sending a request, one SQLite transaction advances the generator cursor
and reserves its candidate in `pending`. Completing it inserts one unique result
and deletes pending in another transaction. Pause/stop releases leases;
restoration processes pending before generating new candidates. Session cursor
is a decimal BigInteger, including 32-character spaces. Dictionary sessions keep
an immutable snapshot. The seeded random mode uses a finite-space permutation,
not repeated random guesses, so it has no duplicate/exhaustion loop.

Random sequences are deterministic **within Nym Mobile**, but are intentionally
not Python random.Random sequences. Desktop database/checkpoint import is not
implemented. Duplicate avoidance applies within a session; a new session can
intentionally recheck old names.

Exactly-once **stored results** and no skipped reserved candidates are guaranteed
by transaction/uniqueness constraints. A killed or canceled HTTP request whose
response was not committed may be sent again on resume. A stateless remote HTTP
endpoint cannot provide exactly-once network delivery across process death.

The non-exported foreground dataSync service persists while the app is in the
background or removed from Recents. Pause ends foreground execution and keeps
a normal resumable notification. No boot receiver or automatic force-stop
restart is attempted. Process death marks an active session interrupted at the
next launch, preserving pending and cursor. Android 15+ imposes a six-hour
background dataSync budget; `onTimeout()` stops promptly and retains the
checkpoint. Battery restrictions, OEM termination, force-stop and reboot can
interrupt execution. See [foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

HTTPS-proxy tunneling uses a bounded loopback relay on Android so Android 10's
TLS provider receives a real socket descriptor for nested TLS. The relay carries
only encrypted target traffic, closes with the transport and uses two blocking
relay threads per pooled tunnel. SOCKS5 uses the actual connected socket
rather than a descriptor-less delegate. No hidden Android API is used.

## Build and test

JDK 17, Android SDK platform 36 and build tools 35.0.0. Gradle 8.13 wrapper,
AGP 8.13.2, Kotlin 2.2.20. The wrapper distribution is checksum-pinned.

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

The Android workflow builds a signed debug APK/checksum and runs real emulator
tests on API 29 and 36. Existing CI runs Python tests, Ruff and the Windows
PyInstaller build. Network tests use loopback MockWebServer, not Discord bulk
traffic. Instrumentation covers database reopening/recovery, duplicate writes,
dictionary snapshots, encrypted config, CSV/filtering, UI navigation/recreation,
landscape, foreground/background operation, notification controls, pause/resume,
cooldown, and CPU/PSS sampling. Staged instrumentation also force-stops and manually relaunches the actual app,
then resumes an interrupted seeded-random checkpoint with an in-flight candidate.
Authenticated SOCKS5, HTTP 407 and nested proxy/target TLS are exercised with
Android\'s real socket and TLS providers. This bugfix uses only mocked networking. The 1800-second cooldown scenario,
notification Stop, blocked request body, and real force-stop cooldown recovery
are exercised on both emulators.
Screenshots are captured from the running app by
UiAutomator, with real engine results from controlled mock responses. Screenshot
fixtures are not claimed to be live Discord availability.

Performance samples are emulator process CPU and PSS, not physical-device
battery or throughput claims. No physical Android VPN combination is claimed
tested. The [debug prerelease](https://github.com/atlasru/nym/releases/tag/nym-mobile-v0.1.1-debug)
contains `VALIDATION.md`, exact mocked 429 Stop timings, CPU/PSS samples and the full
`Nym_Mobile_0.1.1_validation.zip` reports. Successful CI publishes debug artifacts;
no production release signing credentials are used.

## Modules

| Component | Location |
| --- | --- |
| Generation, classification, paced engine, transport, proxies | `core/src/main` |
| Transactional SQLite store and encrypted DataStore settings | `app/.../data` |
| Foreground execution and network capabilities | `ScanService`, `NetworkMonitor` |
| UI state and commands | `ui/NymViewModel` |
| Four native Compose destinations | `ui/NymApp` |

The Kotlin rewrite reuses desktop protocol, generation tokens, character set,
classifications and scheduling concepts while removing Python runtime overhead.
The stronger transactional checkpoint model and global mobile cooldown policy
are deliberate mobile changes. MIT license follows the source project.

## Actual emulator screenshots

Captured by UiAutomator from the running APK, with loopback test results.

![Home](https://github.com/atlasru/nym/releases/download/nym-mobile-v0.1.1-debug/01-home-running.png)
![Results](https://github.com/atlasru/nym/releases/download/nym-mobile-v0.1.1-debug/03-results.png)
![Settings](https://github.com/atlasru/nym/releases/download/nym-mobile-v0.1.1-debug/05-settings.png)
