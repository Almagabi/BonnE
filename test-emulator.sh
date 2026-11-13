#!/usr/bin/env bash
set -euo pipefail
cd android-screen-agent
gradle --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant ai.bonne.screenagent android.permission.POST_NOTIFICATIONS || true
# The package must be brought out of the stopped state, and restricted
# settings must be explicitly allowed for a sideloaded AccessibilityService.
adb shell am start -n ai.bonne.screenagent/.MainActivity
sleep 2
adb shell cmd appops set ai.bonne.screenagent ACCESS_RESTRICTED_SETTINGS allow
SVC="ai.bonne.screenagent/ai.bonne.screenagent.ReplayService"
CUR="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
[ "$CUR" = null ] && CUR=""
case "$CUR" in
  *"$SVC"*) NEW="$CUR" ;;
  "") NEW="$SVC" ;;
  *) NEW="$CUR:$SVC" ;;
esac
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell dumpsys accessibility | grep -E -A 7 "Bound services|Enabled services" | head -35 || true
# Dismiss Android's first-boot immersive-mode tip ("Viewing full screen").
adb shell input tap 825 510 || true
sleep 3
adb shell dumpsys accessibility > emulator-accessibility.txt || true
grep -E -A 6 -B 4 "ai.bonne.screenagent" emulator-accessibility.txt | head -45 || true
python3 ../test-emulator.py
