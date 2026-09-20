# SyncPlay (Android) — Phases 1–2

Local-network multi-device synchronized audio streaming. **No internet required.**

| Phase | Status | Scope |
|-------|--------|--------|
| **1** | Done | NSD discovery, TCP host/client, heartbeat, Compose UI |
| **2** | Done | Custom NTP clock sync (ms offset) over the control channel |
| **3+** | Next | Audio capture / sync playback |

## Architecture (MVVM)

```
UI (Compose)
  HomeScreen / HostScreen / ClientScreen
        │
   ViewModels
        │
   PartyRepository
        ├── NsdHelper              ← `_audio_sync._tcp.`
        ├── TcpHostServer          ← multi-client + PING + SYNC_RES
        ├── TcpClient              ← HELLO + PONG + SYNC_REQ loop
        ├── TimeSyncManager        ← NTP offset / getSyncedTimeMs()
        └── MulticastLockManager
```

### Wire protocol

Line-delimited JSON over TCP (default port `9090`):

| Message | Direction | Purpose |
|---------|-----------|---------|
| `HELLO` / `WELCOME` | handshake | identity + session |
| `PING` / `PONG` | heartbeat | liveness (every 2s) |
| `SYNC_REQ` / `SYNC_RES` | NTP | T1–T4 clock sync (every 3–5s) |
| `DISCONNECT` | either | graceful teardown |

### Phase 2 NTP

```
RTT    = (T4 - T1) - (T3 - T2)
Offset = ((T2 - T1) + (T3 - T4)) / 2
```

- **Host** → `TimeSyncManager.getSyncedTimeMs()` = `System.currentTimeMillis()`
- **Client** → `System.currentTimeMillis() + Offset`
- Samples with RTT > 500ms are discarded; the lowest-RTT sample in an 8-deep window wins.

## Run on devices

1. Open in Android Studio (Ladybug+ / AGP 8.7).
2. Install on 2–3 phones on the same Wi‑Fi or hotspot.
3. Grant nearby Wi‑Fi / location permission.
4. A → **Host Party**; B/C → **Join Party**.
5. Client shows *Clock synced · offset X ms · Y ms RTT* after the first NTP exchange.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Key packages

| Path | Role |
|------|------|
| `data/sync/TimeSyncManager.kt` | Singleton NTP offset + sync loop |
| `data/network/TcpHostServer.kt` | Host sockets + immediate SYNC_RES |
| `data/network/TcpClient.kt` | Client + periodic SYNC_REQ |
| `data/network/NsdHelper.kt` | NSD advertise / discover |
| `data/repository/PartyRepository.kt` | Orchestration |
