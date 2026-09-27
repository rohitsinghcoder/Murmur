#!/usr/bin/env bash
# Downloads what isn't in git: the sherpa-onnx Android library (~50 MB) and the
# Nemotron speech model (~650 MB). Safe to re-run; finished downloads are skipped.
#
# --npu [SOC]  Also set up the NPU version for Snapdragon phones: Qualcomm's QNN runtime is
#              added to sherpa-onnx, and Nemotron 3.5 compiled for the phone's chip is
#              downloaded (~420 MB). SOC is e.g. SM8850 (Snapdragon 8 Elite Gen 5); without it,
#              it's read from the phone connected over adb.
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION=1.13.8
MODEL=sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25
RELEASES=https://github.com/k2-fsa/sherpa-onnx/releases/download
# A sherpa-onnx demo APK built with QNN; the only published source of its QNN-enabled JNI
# library. Qualcomm's runtime libraries come from the same APK.
QNN_APK=https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/qnn-vad-asr-simulated-streaming/$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION-qnn-arm64-v8a-simulated_streaming_asr-ja-SM8850_reazonspeech_zipformer_transducer_ja_5s.apk
# Chips sherpa-onnx publishes compiled Nemotron 3.5 models for.
NPU_SOCS="SM8450 SM8475 SM8550 SM8650 SM8750 SM8845 SM8850"

npu=false
soc=""
if [ "${1:-}" = "--npu" ]; then
    npu=true
    soc="${2:-}"
    if [ -z "$soc" ]; then
        soc=$(adb shell getprop ro.soc.model 2>/dev/null | tr -d '\r') ||
            { echo "No phone found over adb; pass the chip, e.g. --npu SM8850." >&2; exit 1; }
    fi
    case " $NPU_SOCS " in
        *" $soc "*) ;;
        *) echo "No NPU model for chip '$soc'. Supported: $NPU_SOCS" >&2; exit 1 ;;
    esac
fi

download() { # url, destination
    curl -fL --progress-bar -o "$2.part" "$1"
    mv "$2.part" "$2"
}

aar="app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
if [ ! -f "$aar" ]; then
    echo "Downloading sherpa-onnx $SHERPA_VERSION..."
    mkdir -p app/libs
    download "$RELEASES/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar" "$aar"
fi

if [ ! -f "models/$MODEL/tokens.txt" ]; then
    echo "Downloading the speech model (~650 MB)..."
    mkdir -p models
    download "$RELEASES/asr-models/$MODEL.tar.bz2" "models/$MODEL.tar.bz2"
    echo "Unpacking..."
    tar -xjf "models/$MODEL.tar.bz2" -C models
    rm "models/$MODEL.tar.bz2"
fi

if $npu; then
    qnn_aar="app/libs/sherpa-onnx-$SHERPA_VERSION-qnn.aar"
    if [ ! -f "$qnn_aar" ]; then
        echo "Downloading sherpa-onnx with Qualcomm QNN (~350 MB, only a few libraries are kept)..."
        work=$(mktemp -d)
        download "$QNN_APK" "$work/qnn.apk"
        # The JNI library built with QNN, plus Qualcomm's runtime for every NPU generation
        # (Hexagon v68 to v81), so the app works on any supported Snapdragon.
        mkdir -p "$work/jni/arm64-v8a"
        unzip -q -j "$work/qnn.apk" -d "$work/jni/arm64-v8a" \
            'lib/arm64-v8a/libsherpa-onnx-jni.so' 'lib/arm64-v8a/libonnxruntime.so' \
            'lib/arm64-v8a/libQnnHtp.so' 'lib/arm64-v8a/libQnnSystem.so' \
            'lib/arm64-v8a/libQnnHtpV*Stub.so' 'lib/arm64-v8a/libQnnHtpV*Skel.so'
        # Same Kotlin API as the plain library, with the arm64 native code swapped out.
        cp "$aar" "$work/qnn.aar"
        (cd "$work" && zip -q -d qnn.aar 'jni/*' && zip -q -0 qnn.aar jni/arm64-v8a/*.so)
        mv "$work/qnn.aar" "$qnn_aar"
        rm -r "$work"
    fi

    npu_model="sherpa-onnx-qnn-$soc-binary-nemotron-3.5-asr-streaming-0.6b-560ms"
    if [ ! -f "models/$npu_model/tokens.txt" ]; then
        echo "Downloading the NPU speech model for $soc (~420 MB)..."
        mkdir -p models
        download "$RELEASES/asr-models-qnn-binary-3/$npu_model.tar.bz2" "models/$npu_model.tar.bz2"
        echo "Unpacking..."
        tar -xjf "models/$npu_model.tar.bz2" -C models
        rm "models/$npu_model.tar.bz2"
    fi
    echo "$soc" > "models/$npu_model/soc.txt"
fi

echo "Done. Next: ./gradlew installDebug, then scripts/install-model.sh"
