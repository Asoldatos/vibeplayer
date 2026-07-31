# VibePlayer

Upload an MP3, and the app converts it into a vibration waveform driven entirely
by the phone's vibration motor — **no audio is ever played**. The mp3 is decoded
only to measure its amplitude over time; that decoded audio never reaches a
speaker or `AudioTrack`.

## How it works
1. You pick an mp3 via the system file picker.
2. `MediaExtractor` + `MediaCodec` decode it to raw PCM (this is analysis only —
   nothing is routed to audio output).
3. The app computes RMS loudness in ~50ms windows across the whole track.
4. Loudness is normalized and mapped to vibration motor amplitude (1–255) with a
   square-root perceptual curve so quiet parts are still felt.
5. Consecutive similar-amplitude windows are merged into a compact waveform and
   handed to `VibrationEffect.createWaveform(timings, amplitudes, -1)`.
6. Hitting "Play Vibration" runs that whole waveform on the vibration motor.

Requires a phone with a vibration motor; amplitude-varying vibration ("buzz louder
for loud parts") additionally requires `hasAmplitudeControl()` (true on the vast
majority of phones from the last ~8 years). On older/cheaper motors without
amplitude control, playback still works but is just on/off pulses.

## Getting a compiled APK — no local setup needed
This repo includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`)
that builds the APK for you automatically:

1. Create a new GitHub repo and push this project to it.
2. GitHub Actions will run automatically (check the "Actions" tab).
3. When the build finishes, open the run → **Artifacts** → download
   `VibePlayer-debug-apk`. Unzip it to get `app-debug.apk`.
4. Transfer that APK to your phone and install it (you'll need to allow
   "install unknown apps" for whatever app you use to open it).

You can also trigger a build manually anytime from the Actions tab
("Run workflow" button) without needing a new push.

## Building locally instead
If you prefer Android Studio:
1. Open this folder as a project (Android Studio will offer to set up the
   Gradle wrapper automatically — accept it).
2. Let it sync/download dependencies.
3. Run ▶ on a device or `Build > Build Bundle(s)/APK(s) > Build APK(s)`.

## Tuning
In `AudioToVibration.kt`:
- `WINDOW_MS` — resolution of the vibration envelope (default 50ms; lower =
  more responsive but motors may not physically keep up).
- `tolerance` in `buildWaveform` — how aggressively similar amplitudes get
  merged (lower = more detail, larger waveform array).
