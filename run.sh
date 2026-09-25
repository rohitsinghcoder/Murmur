#!/data/data/com.termux/files/usr/bin/bash
# Build, install over wireless adb, and launch Murmur.
set -e
cd "$(dirname "$0")"
gradle assembleDebug -q --console=plain
# adb runs in TCP mode on a fixed port (set once per boot with `adb tcpip 5555`),
# so reconnecting is always the same command.
adb connect localhost:5555 >/dev/null
export ANDROID_SERIAL=localhost:5555
timeout 90 adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.murmur.app/.MainActivity
