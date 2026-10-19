#!/usr/bin/env bash
# 仅在可丢弃的云端模拟器执行；替换测试包，实测最终签名正式包能否冷启动。
set -euo pipefail
EVIDENCE=app/build/release-evidence
mkdir -p "$EVIDENCE"
adb uninstall com.newspaper.helper >/dev/null
adb install app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am force-stop com.newspaper.helper
adb shell am start -W -n com.newspaper.helper/.MainActivity | tee "$EVIDENCE/cold-start.txt"
grep -q 'Status: ok' "$EVIDENCE/cold-start.txt"
sleep 5
adb shell pidof com.newspaper.helper | tee "$EVIDENCE/process.txt"
adb logcat -b crash -d > "$EVIDENCE/crash-log.txt"
if grep -q 'FATAL EXCEPTION' "$EVIDENCE/crash-log.txt"; then
  echo 'Release cold start produced an Android crash.' >&2
  exit 1
fi
adb shell screencap -p /sdcard/Download/dubao-release-home.png
adb pull /sdcard/Download/dubao-release-home.png "$EVIDENCE/home.png"
