# SyncPlay (Android) — Phases 1–4

Local-network multi-device synchronized audio streaming. **No internet required.**

| Phase | Status | Scope |
|-------|--------|--------|
| **1** | Done | NSD discovery, TCP host/client, heartbeat |
| **2** | Done | Custom NTP clock sync |
| **3** | Done | System audio capture + UDP PCM + scheduled playback |
| **4** | Done | Calibration beep, L/R channel routing, 8D pan |

## Phase 4 highlights

- **Calibration Beep** — Host toggles a 1 kHz / 50 ms metronome (every 1 s) with synced PTS so clients align Bluetooth latency sliders until clicks fuse.
- **Speaker channels** — Per-client `STEREO` / `LEFT_CHANNEL` / `RIGHT_CHANNEL` via TCP `CHANNEL_ASSIGN`; host remaps PCM before UDP unicast.
- **8D Experience** — Host LFO sine/cosine pan over ~8 s cycles before channel split.

## Architecture

```
Host capture OR CalibrationToneGenerator
        → Spatial8DProcessor (optional)
        → PcmChannelRouter (per client)
        → UdpAudioBroadcaster (PTS = syncedNow + 200ms)
Client  → ScheduledAudioPlayer (+ manualOffsetMs + channel safety net)
```

## Run

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

1. Host Party → optionally **Start Calibration Beep** first  
2. Clients Join → adjust latency sliders until beeps are one click  
3. Assign Left / Right / Stereo per device  
4. Toggle **8D Experience** · **Start Audio Stream** for system audio  

## Key packages

| Path | Role |
|------|------|
| `data/audio/CalibrationToneGenerator.kt` | Metronome PCM |
| `data/audio/PcmChannelRouter.kt` | L/R/Stereo remap |
| `data/audio/Spatial8DProcessor.kt` | LFO pan |
| `service/AudioCaptureService.kt` | Capture + calibration modes |
| `ui/components/HostControls.kt` | Toggles + channel dropdown |
