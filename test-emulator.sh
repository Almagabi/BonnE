#!/usr/bin/env bash
set -euo pipefail
cd android-screen-agent
gradle --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant ai.bonne.screenagent android.permission.POST_NOTIFICATIONS || true
adb shell am start -n ai.bonne.screenagent/.MainActivity
sleep 5
# Dismiss the first-boot immersive-mode tip, before Accessibility is enabled.
adb shell input tap 825 510 || true
sleep 3
python3 ../test-emulator.py
