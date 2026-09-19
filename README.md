# SyncPlay (Android) — Phase 1

Local-network multi-device synchronized audio streaming. **Phase 1** ships host/client discovery, persistent TCP control sockets, heartbeat, and a Compose UI to validate connections across 2–3 phones on the same Wi‑Fi or hotspot (no internet required).

## Architecture (MVVM)

```
UI (Compose)
  HomeScreen / HostScreen / ClientScreen
        │
   ViewModels (HostViewModel, ClientViewModel)
        │
   PartyRepository          ← single source of truth
        ├── NsdHelper       ← register + discover `_audio_sync._tcp.`
        ├── TcpHostServer   ← concurrent client sockets + host→client PING
        ├── TcpClient       ← connect + HELLO/WELCOME + PONG
        └── MulticastLockManager
```

### Wire protocol

Line-delimited JSON over TCP (default port `9090`):

| Message   | Direction        | Purpose                          |
|-----------|------------------|----------------------------------|
| `HELLO`   | Client → Host    | deviceId + deviceName            |
| `WELCOME` | Host → Client    | hostId, hostName, sessionId      |
| `PING`    | Host → Client    | heartbeat (every 2s)             |
| `PONG`    | Client → Host    | RTT / liveness                   |
| `DISCONNECT` | either        | graceful teardown                |

Peers with no traffic for **6s** are dropped.

## Run on devices

1. Open the project in Android Studio (Ladybug+ / AGP 8.7).
2. Install on two (or three) physical devices on the **same Wi‑Fi** or one phone’s **hotspot**.
3. Grant nearby Wi‑Fi / location permission when prompted.
4. Device A → **Host Party**. Device B/C → **Join Party**.
5. Host screen shows live client names, IPs, and RTT. Client shows *Searching…* then *Connected to [Host]*.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Key packages

| Path | Role |
|------|------|
| `data/network/NsdHelper.kt` | NSD advertise / discover |
| `data/network/TcpHostServer.kt` | Multi-client TCP host + heartbeat |
| `data/network/TcpClient.kt` | Persistent client socket |
| `data/repository/PartyRepository.kt` | Orchestration + Flows |
| `ui/host`, `ui/client`, `ui/home` | Compose screens |

## Permissions

Declared in `AndroidManifest.xml`:

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_MULTICAST_STATE` (mDNS reliability)
- `NEARBY_WIFI_DEVICES` (API 33+) / location (API ≤32) for NSD

## Next phases (out of scope)

Audio capture/encode, clock sync, and synchronized playback build on this control channel.
