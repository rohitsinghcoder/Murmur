# Murmur

Private voice typing for Android. A small bubble sits above your keyboard in any app: tap it,
speak, tap again, and your words are typed into the text field. Speech recognition runs
entirely on the phone. No internet, no account, nothing leaves the device.

- Floating bubble above the keyboard (Wispr Flow style), draggable
- Live transcript while you speak, with punctuation and capitals
- Removes "um", "uh" and similar filler words
- Writes numbers the way you'd type them: "twenty twenty five" → 2025, "fifty percent" → 50%,
  "three thirty pm" → 3:30 PM, "five hundred rupees" → ₹500
- Searchable history of everything you've dictated
- English only for now

It uses NVIDIA's [Nemotron Speech Streaming](https://huggingface.co/nvidia) model (0.6B
parameters, int8) through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

## Requirements

- An Android phone with **Android 10 or newer** and a **64-bit ARM chip** (almost every phone
  from the last several years). About 1 GB of free storage; a phone with 8 GB+ RAM is
  recommended, since the model stays loaded while Murmur is on.
- A computer with **JDK 17**, the **Android SDK** (installing
  [Android Studio](https://developer.android.com/studio) is the easiest way to get both) and
  **adb**. `curl`, `tar` and `bash` for the setup scripts (macOS, Linux, or WSL/Git Bash on
  Windows).
- USB debugging enabled on the phone (Settings → About phone → tap *Build number* 7 times,
  then Settings → Developer options → *USB debugging*).

## Setup

### 1. Get the code and download the model

```bash
git clone https://github.com/rohitsinghcoder/Murmur.git
cd Murmur
./scripts/fetch-deps.sh
```

This downloads the two things that are too large for git:

| What | Size | Goes to |
|---|---|---|
| [sherpa-onnx 1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) Android library (`.aar`) | ~50 MB | `app/libs/` |
| [Nemotron Speech Streaming EN 0.6B, 560 ms, int8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~650 MB | `models/` |

### 2. Build and install the app

Connect the phone with USB (accept the "Allow USB debugging?" prompt), then:

```bash
./gradlew installDebug
```

If Gradle can't find the Android SDK, create a file `local.properties` in the project folder
containing `sdk.dir=/path/to/Android/sdk` (Android Studio shows the path under
Settings → Languages & Frameworks → Android SDK).

### 3. Copy the model onto the phone

```bash
./scripts/install-model.sh
```

The model has to live in the app's private storage, which only the app itself can write to,
so the script pushes it to the phone and copies it in as the app (`adb shell run-as`). This
only works for the debug build installed in step 2.

### 4. Set it up on the phone

Open **Murmur** and follow the checklist on the main screen:

1. **Microphone:** allow it.
2. **Bubble:** turn on Murmur under Settings → Accessibility. Android only lets an
   accessibility service see and fill text fields in other apps; that's what types your words.
3. **Battery:** allow unrestricted battery use, so Android doesn't stop Murmur in the background.
4. Tap **Start**. The first start loads the model and takes a few seconds.

Now tap into any text field: the bubble appears above the keyboard. Tap it, speak, and tap it
again to type what you said. Drag the bubble to move it.

## Good to know

- **Why the "Start" button?** Android only lets an app use the microphone in the background if
  it started doing so while the app was on screen. After a reboot, or if Android closes Murmur,
  open it and tap Start again.
- **"Restricted setting" when enabling the accessibility service:** Android 13+ blocks this for
  apps installed from outside the Play Store. Go to Settings → Apps → Murmur → ⋮ (top right)
  → *Allow restricted settings*, then try again.
- **Text went to the clipboard instead:** some apps don't allow text to be typed in from outside.
  Murmur copies it to the clipboard instead, so you can paste it.
- **Speed test:** the main screen can transcribe a sample recording to show how fast the model
  runs on your phone.

## Building on the phone itself (Termux)

The app was developed entirely on an Android phone in Termux. There, `./run.sh` builds,
installs over wireless adb (`localhost:5555`) and launches it. Google's `aapt2` doesn't run on
ARM, so point Gradle at Termux's own build in `~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

## Project layout

Everything is in `app/src/main/java/com/murmur/app/`:

| File | What it does |
|---|---|
| `Engine.kt` | Loads the speech model once; `Transcriber` streams audio in and text out |
| `DictationService.kt` | Microphone foreground service: records, transcribes, saves to history |
| `BubbleService.kt` | Accessibility service: shows the bubble and types text into the focused field |
| `Bubble.kt` | The bubble and the listening pill |
| `Cleanup.kt`, `Numbers.kt` | Filler-word removal and number formatting |
| `MainActivity.kt`, `HistoryScreen.kt` | Setup checklist, try-it box, speed test, history |
| `History.kt` | Dictation history, stored privately on the phone |
| `State.kt`, `Theme.kt` | Shared state, theme and preferences |
