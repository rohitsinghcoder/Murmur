#!/usr/bin/env bash
# Compares the NPU and CPU speech backends on the connected phone (debug build).
# Transcribes the speed-test sample on each and prints load time, decode time and CPU time.
# Usage: scripts/benchmark.sh [runs] [wav path relative to the app's files folder]
set -euo pipefail
adb logcat -c
adb shell am start -W -n com.murmur.app/.MainActivity >/dev/null
adb shell am broadcast -n com.murmur.app/.BenchmarkReceiver --ei runs "${1:-3}" ${2:+--es wav "$2"} >/dev/null
echo "Running (takes a minute)..."
adb logcat -s MurmurBench:I '*:F' | while IFS= read -r line; do
    echo "$line" | sed 's/.*MurmurBench: //'
    case "$line" in *MurmurBench*done*) break ;; esac
done
