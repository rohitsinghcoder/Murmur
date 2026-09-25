# Murmur

Private, on-device voice typing for Android with a floating bubble (Wispr Flow style).
English speech recognition runs fully offline. Built and run on the phone (OnePlus 15, Termux).

## Build & run
- `./run.sh` — builds, installs over adb (`localhost:5555`) and launches. After a reboot adb TCP
  mode must be re-enabled (connect via the Wireless debugging port, then `adb tcpip 5555`).
- `app/libs/sherpa-onnx-1.13.8.aar` and the model are not in git: `scripts/fetch-deps.sh`
  downloads both, `scripts/install-model.sh` copies the model onto the phone. README.md has the
  full setup for other machines (`./gradlew installDebug`).
- The Termux-only `aapt2` override lives in `~/.gradle/gradle.properties`, not the project,
  so the project builds on a normal computer too.
- After installing, open the app and tap **Start** (Android only allows the mic service to be
  started from a visible activity).

## Speech model
- NVIDIA Nemotron Speech Streaming EN 0.6B, 560 ms chunks, int8
  (`sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25`, sherpa-onnx `asr-models`
  release). feat_dim 128.
- Must live in the app's private `files/model/` (encoder/decoder/joiner `.onnx`, `tokens.txt`,
  optional `sample.wav` for the speed test). Files pushed to `Android/data/...` are unreadable to
  the app. `scripts/install-model.sh` pushes to /data/local/tmp and copies in with `run-as`.

## Layout (`app/src/main/java/com/murmur/app/`)
- `Engine.kt` — loads the model once; `Transcriber` streams audio and returns running text.
- `DictationService.kt` — microphone foreground service: records, transcribes, saves to history.
- `BubbleService.kt` — accessibility service: shows the bubble above the keyboard, finds the
  focused field across windows and inserts text (SET_TEXT, falling back to paste).
- `Bubble.kt` — bubble/pill UI. The overlay window has fixed sizes; only Compose animates.
- `MainActivity.kt`, `HistoryScreen.kt` — setup checklist, try-it box, speed test, history.
- `Cleanup.kt`, `Numbers.kt` — filler-word removal and spoken numbers to digits, applied to
  the live and final transcript.
- `History.kt` — dictation history as JSON lines in app-private storage.
- `State.kt`, `Theme.kt` — shared dictation state, theme and bubble-position prefs.

## Device quirks (OnePlus)
- adb `pm grant` and `screenrecord` are blocked; `run-as` works for debug builds.
- Android 14+: accessibility services and overlays are NOT exempt from the background mic
  restriction — hence the "Start" step.
