# AirPods Pro 2 — Android BLE Battery & Finder

Kotlin / Compose / Android. Reads AirPods Pro 2 battery from BLE Continuity
advertisements, shows a persistent notification, has an iOS-style overlay
popup, a Quick Settings tile, an in-app Geiger finder, and a self-updater.

## Hard-won facts about AirPods Pro 2 on stock Android

These are non-obvious and discovered through this codebase. If you change
the BLE pipeline, re-read them first.

- **`0x07 len=25` is the only readable battery packet.** Apple's Continuity
  proximity-pairing subtype 0x07 has two variants. The 27-byte (`0x07 0x19`)
  packet contains battery in nibbles — that's what `AirPodsParser.parse()`
  decodes. The 19-byte (`0x07 0x11`) variant is encrypted with an IRK that
  only iCloud-paired iPhones get. We can't read it.
- **Recent firmware emits `len=25` rarely.** On the user's AirPods Pro 2
  (Lightning, 2022) with ~2024 firmware, the unencrypted packet is mostly
  only emitted when the case lid opens (~30s window) or during pairing.
  Continuous live battery is impossible without root.
- **`setReportDelay(1)` is mandatory.** With `reportDelay=0` (default
  immediate callbacks), Android drops the brief lid-open bursts. Switching
  to batch scan with `reportDelay > 0` makes the BT controller do the
  filtering at firmware level — this is what OpenPods/MaterialPods do and
  it's the difference between zero captures and hundreds.
- **AAP over L2CAP is gated.** Apple's accessory protocol on PSM 0x1001
  would give live per-pod battery, but `BluetoothDevice.createL2capChannel`
  opens BLE L2CAP CoC, not classic L2CAP. AirPods only accept AAP on
  classic. Classic L2CAP is privileged-only on Android. We tried, it
  fails with `read failed, ret -1` in ~3ms.
- **`getMetadata` is `BLUETOOTH_PRIVILEGED`.** Same for the legacy
  `getBatteryLevel()`. Both throw SecurityException unconditionally.
- **`AT+IPHONEACCEV` exists but isn't emitted.** Modern AirPods firmware
  doesn't send the HFP vendor battery event. We listen for it (registered
  with the Apple company-id category on `BluetoothHeadset.ACTION_VENDOR_
  SPECIFIC_HEADSET_EVENT`) but it never fires for this user.
- **Neighbors trip the parser.** Other people's AirPods nearby will also
  emit `0x07 len=25` if they're in pairing/lid-open. We filter with
  `MIN_RSSI_DBM = -65` and a session-wide model-id lock at `STRONG_RSSI_DBM
  = -55`. See `AirPodsBleService.onScanResult`.
- **byte 8 parity does NOT mean "lid closed".** Looked obvious; isn't. The
  counter increments on lid events but doesn't track current state. Don't
  surface a "lid closed" hint based on it.

## Architecture

```
MainActivity (ComponentActivity)
  └─ DashboardScreen (Compose) — 3-tab NavigationBar
       ├─ Dashboard tab: status, L/R/case batteries, proximity, geiger, controls
       ├─ Info tab: version, recent commits (via UpdateChecker.recentCommits)
       └─ Settings tab: theme, overlay perm, save/view logs, clear cache

AirPodsBleService (LifecycleService, foreground type CONNECTED_DEVICE)
  ├─ BluetoothLeScanner with batch mode (reportDelay=1)
  ├─ Screen on/off receiver → switches LOW_LATENCY ↔ BALANCED
  ├─ ConnectedAirPodsMonitor — listens to HFP/A2DP/LE Audio profile state
  ├─ AirPodsOverlay — SYSTEM_ALERT_WINDOW popup when fresh open
  ├─ AirPodsAapClient — best-effort L2CAP (logs failure once, gives up)
  └─ BluetoothBroadcastReceiver — auto-starts on AirPods connect

AirPodsRepository (singleton, StateFlow<AirPodsState>)
  - onSnapshot(): persists only FULL snapshots (all 3 values)
  - restore on init wipes anything >24h old
```

## Build & deploy

CI lives in `.github/workflows/build-apk.yml`. Push to `main`:
1. Runs `./gradlew :app:testDebugUnitTest` (unit tests for parser only).
2. Runs `./gradlew :app:assembleDebug`.
3. Publishes APK to the `latest` release as `app-debug.apk`.

Direct install URL the user uses:
`https://github.com/braianj/air_pods-app/releases/download/latest/app-debug.apk`

Build SHA bakes into `BuildConfig.GIT_SHA` (from `GITHUB_SHA` env). The
in-app updater compares the installed SHA against the release body's
`from commit XXXXXX` line.

**A static `app/debug.keystore` is committed** so every CI build signs
identically — without this, every update would fail with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Sandbox constraints in remote-execution Claude sessions

- `cdn.iconscout.com`, `citypng.com`, `dl.google.com`, `maven.google.com`
  are blocked — `Host not in allowlist`. Can't fetch reference images,
  can't run `./gradlew` locally (Maven resolution fails).
- Only way to verify a build: wait for CI to publish.
- GitHub MCP tools are repo-scoped to `braianj/air_pods-app`. Use
  `mcp__github__search_code` (which works across all of GitHub) for
  reference implementations (e.g. OpenPods).
- Bash CAN reach the GitHub API but the unauthenticated rate limit is
  60/hour — prefer the MCP tools.

## Tone & decisions in this repo

- Spanish replies, technical content honest about limits.
- Battery-impact diagnostics live in the log so the user can audit drain:
  `battery(1min): uptime=X drop=Y% scanRunning=Z`.
- When a feature is impossible (e.g. case beep / find-my chirp), say so
  plainly and offer the closest workaround.
