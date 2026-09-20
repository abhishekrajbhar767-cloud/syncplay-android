# SyncPlay (Android) — Phases 1–3

Local-network multi-device synchronized audio streaming. **No internet required.**

| Phase | Status | Scope |
|-------|--------|--------|
| **1** | Done | NSD discovery, TCP host/client, heartbeat, Compose UI |
| **2** | Done | Custom NTP clock sync (ms offset) over TCP |
| **3** | Done | System audio capture + UDP PCM streaming + scheduled playback |
| **4+** | Next | Codec compression, Wi‑Fi Direct hardening, UX polish |

## Architecture (MVVM)

```
UI (Compose)
  Home / Host (Start Audio Stream) / Client (latency slider)
        │
   ViewModels
        │
   PartyRepository
        ├── NsdHelper + TcpHostServer / TcpClient
        ├── TimeSyncManager
        ├── AudioCaptureService  ← MediaProjection + AudioPlaybackCapture
        ├── UdpAudioBroadcaster  ← Host UDP + PTS header
        ├── UdpAudioReceiver     ← Client UDP
        └── ScheduledAudioPlayer ← play when synced clock ≥ PTS + manualOffset
```

### Phase 3 audio path

1. Host taps **Start Audio Stream** → MediaProjection consent → foreground service.
2. `AudioPlaybackCapture` reads system PCM (48 kHz stereo 16-bit) in ~10 ms frames.
3. Each UDP datagram carries `presentationTimestampMs = getSyncedTimeMs() + 200`.
4. Clients receive frames, buffer them, and `AudioTrack`-play exactly at PTS (+ optional Bluetooth offset slider).

### Wire protocol (TCP control)

| Message | Purpose |
|---------|---------|
| `HELLO` / `WELCOME` / `PING` / `PONG` | Phase 1 |
| `SYNC_REQ` / `SYNC_RES` | Phase 2 NTP |
| `AUDIO_SESSION` / `AUDIO_STOP` | Phase 3 UDP session announce |

Audio PCM itself is **UDP-only** (port `9091`).

## Run on devices (Android 10+)

1. Open in Android Studio; install on 2–3 phones (same Wi‑Fi / hotspot).
2. Grant nearby Wi‑Fi, microphone, and notification permissions when asked.
3. Host → **Host Party** → **Start Audio Stream** → accept screen/audio capture dialog.
4. Clients → **Join Party** → wait for clock sync → play Spotify/YouTube on the host.
5. Use the client **Bluetooth / speaker offset** slider if a BT speaker lags.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Key packages

| Path | Role |
|------|------|
| `service/AudioCaptureService.kt` | Foreground MediaProjection capture |
| `data/audio/SystemAudioCapturer.kt` | AudioPlaybackCapture API |
| `data/audio/UdpAudioBroadcaster.kt` | Host UDP + PTS |
| `data/audio/UdpAudioReceiver.kt` | Client UDP |
| `data/audio/ScheduledAudioPlayer.kt` | Timed `AudioTrack` playback |
| `data/sync/TimeSyncManager.kt` | Shared clock |
