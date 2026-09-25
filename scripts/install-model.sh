#!/usr/bin/env bash
# Copies the speech model into Murmur's private storage on the connected phone.
# Murmur (debug build) must already be installed: `run-as` only works for debuggable apps.
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25
APP=com.murmur.app
src="models/$MODEL"
tmp=/data/local/tmp/murmur

[ -f "$src/tokens.txt" ] || { echo "Model not found; run scripts/fetch-deps.sh first." >&2; exit 1; }
adb shell pm path "$APP" >/dev/null || { echo "Murmur isn't installed; run ./gradlew installDebug first." >&2; exit 1; }

echo "Pushing the model to the phone..."
adb shell mkdir -p "$tmp"
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    adb push "$src/$f" "$tmp/$f"
done
# Optional: a short recording for the in-app speed test.
adb push "$src/test_wavs/0.wav" "$tmp/sample.wav"

# Files adb pushes elsewhere (e.g. Android/data) are unreadable to the app, so copy them
# in as the app itself.
adb shell run-as "$APP" mkdir -p files/model
adb shell run-as "$APP" cp "$tmp/encoder.int8.onnx" "$tmp/decoder.int8.onnx" "$tmp/joiner.int8.onnx" \
    "$tmp/tokens.txt" "$tmp/sample.wav" files/model/
adb shell rm -r "$tmp"
echo "Model installed. Open Murmur on the phone."
