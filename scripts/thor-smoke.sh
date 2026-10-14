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
adb logcat -c || true
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; log "install exit: $?"
cat "$OUT/install.txt"

# Simulated bottom screen (Developer options > Simulate secondary displays).
adb shell settings put global overlay_display_devices 1240x1080/240
sleep 5
adb shell dumpsys display | grep -E 'mDisplayId=|DisplayDeviceInfo|mBaseDisplayInfo' > "$OUT/displays.txt" 2>&1

# Public-domain blargg test ROM via the debug autoload path.
ROM=tests/roms/nes-test-roms/vbl_nmi_timing/1.frame_basics.nes
adb shell mkdir -p /sdcard/Android/data/$PKG/files
adb push "$ROM" /sdcard/Android/data/$PKG/files/autoload.nes > /dev/null 2>&1
# Test expansion that matches the autoload ROM by name.
if [ -f out/expansions/smoke-test.exp ]; then
  adb push out/expansions/smoke-test.exp /sdcard/Download/smoke-test.exp > /dev/null 2>&1
fi

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > "$OUT/launch.txt" 2>&1
log "launched"
sleep 25
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

adb logcat -d > "$OUT/logcat.txt" 2>&1
grep -E "FATAL EXCEPTION|AndroidRuntime|RustyNES" "$OUT/logcat.txt" | head -80 > "$OUT/logcat-app.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "RESULT: crash"; else log "RESULT: no crash"; fi
[ -n "${PID:-}" ] && log "RESULT: process alive" || log "RESULT: process NOT running"
exit 0
