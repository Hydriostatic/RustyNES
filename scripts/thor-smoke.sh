#!/usr/bin/env bash
# Emulator smoke test for the AYN Thor build (runs inside android-emulator-runner).
# Installs the APK, adds a simulated 1240x1080 second display (the Thor's bottom
# panel size), autoloads a public-domain test ROM through the debug build's
# autoload hook, and records screenshots, the activity/display state and logcat.
set -u
APK="$1"; OUT="$2"
mkdir -p "$OUT"
log() { echo "[smoke] $*" | tee -a "$OUT/steps.txt"; }

PKG=$(aapt2 dump badging "$APK" 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p")
[ -n "$PKG" ] || PKG=com.doublegate.rustynes.debug
log "package: $PKG"
adb logcat -G 16M || true
adb logcat -b all -c || true
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; log "install exit: $?"
cat "$OUT/install.txt"

# Simulated bottom screen (Developer options > Simulate secondary displays).
adb shell settings put global overlay_display_devices 1240x1080/240
sleep 5
adb shell dumpsys display | grep -E 'mDisplayId=|DisplayDeviceInfo|mBaseDisplayInfo' > "$OUT/displays.txt" 2>&1

# First launch lets Android create the app's external files dir itself (a dir
# made by `adb shell mkdir` is not always accepted by the storage daemon), then
# stop the app, drop the test ROM + expansions in, and launch again.
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 15
adb shell am force-stop "$PKG"
sleep 2
ROM=tests/roms/nes-test-roms/vbl_nmi_timing/1.frame_basics.nes
adb push "$ROM" /sdcard/Android/data/$PKG/files/autoload.nes > /dev/null 2>&1 && log "pushed autoload ROM"
adb shell mkdir -p /sdcard/Android/data/$PKG/files/expansions
for e in out/expansions/*.exp; do
  [ -f "$e" ] && adb push "$e" /sdcard/Android/data/$PKG/files/expansions/ > /dev/null 2>&1 && log "pushed $e"
done
adb logcat -b all -c || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > "$OUT/launch.txt" 2>&1
log "launched"
sleep 25
adb shell ls -la /sdcard/Android/data/$PKG/files /sdcard/Android/data/$PKG/files/expansions > "$OUT/files.txt" 2>&1
PID=$(adb shell pidof "$PKG" | tr -d '\r')
log "pid after 25s: ${PID:-<none>}"
echo "${PID:-}" > "$OUT/pid.txt"

adb exec-out screencap -p > "$OUT/top.png" 2>/dev/null || true
for d in $(adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null | sed -n 's/^Display \([0-9]*\).*/\1/p'); do
  adb exec-out screencap -d "$d" -p > "$OUT/display-$d.png" 2>/dev/null || true
done
adb shell dumpsys SurfaceFlinger --display-id > "$OUT/sf-displays.txt" 2>&1
adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1
grep -E 'Display #|ActivityRecord.*rustynes' "$OUT/activities.txt" | head -40 | tee -a "$OUT/steps.txt"

sleep 8
adb pull /sdcard/Android/data/$PKG/files/thor-bottom.png "$OUT/bottom-screen-1.png" > /dev/null 2>&1
# Dismiss the first-run dialog on the top screen (bottom-right "Continue"), then look again.
adb shell input keyevent KEYCODE_ENTER
sleep 20
adb exec-out screencap -p > "$OUT/top-2.png" 2>/dev/null || true
adb pull /sdcard/Android/data/$PKG/files/thor-bottom.png "$OUT/bottom-screen.png" > /dev/null 2>&1 && log "bottom screen snapshot saved" || log "no bottom screen snapshot"
# Second pass: tap the top screen's dialog away and check again after more play time.
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp|mTopFocusedDisplayId|mFocusedWindow" > "$OUT/focus.txt" 2>&1
adb shell dumpsys input | grep -iE "FocusedDisplayId|FocusedWindows|focusedWindow" >> "$OUT/focus.txt" 2>&1
cat "$OUT/focus.txt" | tee -a "$OUT/steps.txt"
adb logcat -b all -d > "$OUT/logcat.txt" 2>&1
grep -E "RustyNES-thor|RustyNES-exp" "$OUT/logcat.txt" | tee "$OUT/thor-log.txt" | head -20 | tee -a "$OUT/steps.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime|RustyNES" "$OUT/logcat.txt" | head -80 > "$OUT/logcat-app.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "RESULT: crash"; else log "RESULT: no crash"; fi
[ -n "${PID:-}" ] && log "RESULT: process alive" || log "RESULT: process NOT running"
exit 0
