#!/usr/bin/env bash
set -euo pipefail
cd android-screen-agent
gradle --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb shell pm grant ai.bonne.screenagent android.permission.POST_NOTIFICATIONS || true
adb shell am start -n ai.bonne.screenagent/.MainActivity
sleep 5
# Avoid blind taps: they can click BonnE's Accessibility button.
sleep 2
python3 ../test-smart.py
