#!/usr/bin/env bash
# Downloads what isn't in git: the sherpa-onnx Android library (~50 MB) and the
# Nemotron speech model (~650 MB). Safe to re-run; finished downloads are skipped.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
MODEL=sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25
RELEASES=https://github.com/k2-fsa/sherpa-onnx/releases/download

aar="app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
if [ ! -f "$aar" ]; then
    echo "Downloading sherpa-onnx $SHERPA_VERSION..."
    mkdir -p app/libs
    curl -fL --progress-bar -o "$aar.part" "$RELEASES/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
    mv "$aar.part" "$aar"
fi

if [ ! -f "models/$MODEL/tokens.txt" ]; then
    echo "Downloading the speech model (~650 MB)..."
    mkdir -p models
    curl -fL --progress-bar -o "models/$MODEL.tar.bz2" "$RELEASES/asr-models/$MODEL.tar.bz2"
    echo "Unpacking..."
    tar -xjf "models/$MODEL.tar.bz2" -C models
    rm "models/$MODEL.tar.bz2"
fi

echo "Done. Next: ./gradlew installDebug, then scripts/install-model.sh"
