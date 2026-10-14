#!/usr/bin/env bash
# Launch check for the optimized release build (R8): installs it next to the debug
# build, launches it with a simulated bottom screen and records crash/ANR status.
# The debug-only test hooks (ROM autoload, expansion drop folder) are compiled out
# of release builds, so this checks start-up, both screens and stability only.
set -u
APK="$1"; OUT="$2"
mkdir -p "$OUT"
log() { echo "[release] $*" | tee -a "$OUT/steps.txt"; }
[ -f "$APK" ] || { log "no release test APK"; exit 0; }
PKG=$(aapt2 dump badging "$APK" 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p")
[ -n "$PKG" ] || PKG=com.doublegate.rustynes
adb logcat -b all -c || true
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; log "install exit: $? ($PKG)"
adb shell settings put global overlay_display_devices 1240x1080/240
sleep 3
adb shell am force-stop com.doublegate.rustynes.debug || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 20
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 5
PID=$(adb shell pidof "$PKG" | tr -d '\r')
log "pid: ${PID:-<none>}"
adb exec-out screencap -p > "$OUT/top.png" 2>/dev/null || true
adb shell dumpsys activity activities | grep -E "Display #|ActivityRecord.*rustynes" | grep -v debug | head -12 | tee -a "$OUT/steps.txt"
adb logcat -b all -d > "$OUT/logcat.txt" 2>&1
grep -E "FATAL EXCEPTION|AndroidRuntime: |am_anr" "$OUT/logcat.txt" | head -40 > "$OUT/errors.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "RESULT: crash"; else log "RESULT: no crash"; fi
if grep -q "am_anr" "$OUT/logcat.txt"; then log "RESULT: ANR"; else log "RESULT: no ANR"; fi
[ -n "${PID:-}" ] && log "RESULT: process alive" || log "RESULT: process NOT running"
exit 0
