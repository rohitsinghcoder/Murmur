#!/usr/bin/env bash
# Copies the speech models into Murmur's private storage on the connected phone: the CPU model
# and, if scripts/fetch-deps.sh --npu downloaded one for this phone's chip, the NPU model.
# Murmur (debug build) must already be installed: `run-as` only works for debuggable apps.
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25
APP=com.murmur.app
tmp=/data/local/tmp/murmur

adb shell pm path "$APP" >/dev/null || { echo "Murmur isn't installed; run ./gradlew installDebug first." >&2; exit 1; }
soc=$(adb shell getprop ro.soc.model | tr -d '\r')
npu="models/sherpa-onnx-qnn-$soc-binary-nemotron-3.5-asr-streaming-0.6b-560ms"
cpu="models/$MODEL"
[ -f "$npu/tokens.txt" ] || npu=""
[ -f "$cpu/tokens.txt" ] || cpu=""
[ -n "$npu$cpu" ] || { echo "Model not found; run scripts/fetch-deps.sh first." >&2; exit 1; }

# Files adb pushes elsewhere (e.g. Android/data) are unreadable to the app, so push to a temp
# folder and copy them in as the app itself.
copy_in() { # source folder, app folder, files...
    local src=$1 dest=$2
    shift 2
    adb shell rm -rf "$tmp" && adb shell mkdir -p "$tmp"
    for f in "$@"; do adb push "$src/$f" "$tmp/$f"; done
    adb shell run-as "$APP" mkdir -p "files/$dest"
    adb shell run-as "$APP" sh -c "'cp $tmp/* files/$dest/ && chmod 700 files/$dest && chmod 600 files/$dest/*'"
    adb shell rm -r "$tmp"
}

if [ -n "$cpu" ]; then
    echo "Installing the CPU model..."
    # sample.wav is a short recording for the in-app speed test.
    cp "$cpu/test_wavs/0.wav" "$cpu/sample.wav"
    copy_in "$cpu" model encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt sample.wav
fi
if [ -n "$npu" ]; then
    echo "Installing the NPU model for $soc..."
    copy_in "$npu" model-npu encoder.bin decoder.bin joiner.bin tokens.txt soc.txt
    if [ -z "$cpu" ]; then
        cp "$npu/test_wavs/en.wav" "$npu/sample.wav"
        copy_in "$npu" model sample.wav
    fi
fi
echo "Model installed. Open Murmur on the phone."
