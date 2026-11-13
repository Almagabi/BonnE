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
sleep 5
# Dismiss Android's first-boot immersive-mode tip ("Viewing full screen").
adb shell input tap 825 510 || true
sleep 3
adb shell dumpsys accessibility > emulator-accessibility.txt || true
grep -E -A 6 -B 4 "ai.bonne.screenagent" emulator-accessibility.txt | head -45 || true
python3 ../test-emulator.py
