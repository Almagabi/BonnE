#!/usr/bin/env bash
set -euo pipefail
cd android-screen-agent
gradle --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant ai.bonne.screenagent android.permission.POST_NOTIFICATIONS || true
adb shell settings put secure enabled_accessibility_services ai.bonne.screenagent/.ReplayService
adb shell settings put secure accessibility_enabled 1
sleep 4
adb shell am start -n ai.bonne.screenagent/.MainActivity
sleep 3
python3 ../test-emulator.py
