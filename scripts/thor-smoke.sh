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
adb shell dumpsys window windows | grep -E "Window #[0-9]+ Window\{|mDisplayId=" > "$OUT/windows-first-run.txt" 2>&1
adb shell am force-stop "$PKG"
sleep 2
# Skip the first-run dialogs from here on (they were checked above), via the debug
# build's run-as access to its own preferences.
cat > "$OUT/skip-onboarding.sh" <<'SH'
f=shared_prefs/settings.xml
if [ ! -f "$f" ]; then
  mkdir -p shared_prefs
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"onboardDone\" value=\"true\" />\n</map>\n" > "$f"
elif [ -f "$f" ] && ! grep -q onboardDone "$f"; then
  sed -i 's#</map>#    <boolean name="onboardDone" value="true" />\n</map>#' "$f"
fi
SH
adb push "$OUT/skip-onboarding.sh" /data/local/tmp/skip-onboarding.sh > /dev/null 2>&1
adb shell chmod 755 /data/local/tmp/skip-onboarding.sh
adb shell run-as "$PKG" sh /data/local/tmp/skip-onboarding.sh 2>&1 | tee -a "$OUT/steps.txt"
adb shell "run-as $PKG cat shared_prefs/settings.xml" > "$OUT/prefs.txt" 2>&1
ROM=tests/roms/nes-test-roms/instr_test-v3/rom_singles/01-implied.nes
adb push "$ROM" /sdcard/Android/data/$PKG/files/autoload.nes > /dev/null 2>&1 && log "pushed autoload ROM"
adb shell mkdir -p /sdcard/Android/data/$PKG/files/expansions
for e in out/expansions/*.exp; do
  [ -f "$e" ] && adb push "$e" /sdcard/Android/data/$PKG/files/expansions/ > /dev/null 2>&1 && log "pushed $e"
done
# A sign-in with made-up details: it must reach retroachievements.org and come back
# with the server's refusal, without any game open.
printf 'rustynes-ci-%s\nnot-a-real-password\n' "$RANDOM" > "$OUT/ra-test.txt"
adb push "$OUT/ra-test.txt" /sdcard/Android/data/$PKG/files/ra-test.txt > /dev/null 2>&1
rm -f "$OUT/ra-test.txt"
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
# Key input after the bottom screen opened: must reach the game, never ANR.
adb shell dumpsys window | grep -E "mTopFocusedDisplayId|mCurrentFocus" | tee -a "$OUT/steps.txt"
adb shell input keyevent KEYCODE_DPAD_RIGHT
sleep 8
# Hold a finger on the game panel for 3.5 s: the bottom screen must go back to its menu.
adb shell input -d 2 swipe 620 500 620 500 3500
sleep 3
# Tap "All settings…" on the bottom menu: the Settings sheet must open on display 2.
adb shell input -d 2 tap 324 898
sleep 5
adb shell dumpsys window windows | grep -E "Window #[0-9]+ Window\{|mDisplayId=" > "$OUT/windows-settings.txt" 2>&1
adb exec-out screencap -p > "$OUT/top-with-settings.png" 2>/dev/null || true
python3 - "$OUT/windows-settings.txt" <<'PY' | tee -a "$OUT/steps.txt"
import re, sys
win = None
per = {}
for line in open(sys.argv[1], errors="ignore"):
    m = re.search(r"Window\{\S+ u0 ([^}]*)\}", line)
    if m:
        win = m.group(1)
        continue
    d = re.search(r"mDisplayId=(\d+)", line)
    if d and win and "rustynes" in win:
        per.setdefault(d.group(1), []).append(win)
        win = None
for disp, wins in sorted(per.items()):
    print(f"[smoke] display {disp}: {len(wins)} RustyNES window(s): {wins}")
PY
adb shell input keyevent KEYCODE_BACK
sleep 2
# Dismiss the first-run dialog on the top screen (bottom-right "Continue"), then look again.
adb shell input keyevent KEYCODE_ENTER
sleep 20
adb exec-out screencap -p > "$OUT/top-2.png" 2>/dev/null || true
adb pull /sdcard/Android/data/$PKG/files/thor-menu.png "$OUT/bottom-menu.png" > /dev/null 2>&1
adb pull /sdcard/Android/data/$PKG/files/thor-bottom.png "$OUT/bottom-screen.png" > /dev/null 2>&1 && log "bottom screen snapshot saved" || log "no bottom screen snapshot"
# Second pass: tap the top screen's dialog away and check again after more play time.
# Home: both screens must leave together. (The emulator's simulated second display
# has no home screen of its own, so Home is pressed on the main display here.)
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell dumpsys activity activities | grep -E "Display #|ResumedActivity|topResumedActivity" > "$OUT/after-home.txt" 2>&1
if grep -E "ResumedActivity.*rustynes" "$OUT/after-home.txt" > /dev/null; then
  log "RESULT: after Home, RustyNES is still showing"
  grep -E "rustynes" "$OUT/after-home.txt" | head -4 | tee -a "$OUT/steps.txt"
else
  log "RESULT: after Home, both screens left together"
fi
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp|mTopFocusedDisplayId|mFocusedWindow" > "$OUT/focus.txt" 2>&1
adb shell dumpsys input | grep -iE "FocusedDisplayId|FocusedWindows|focusedWindow" >> "$OUT/focus.txt" 2>&1
cat "$OUT/focus.txt" | tee -a "$OUT/steps.txt"
adb logcat -b all -d > "$OUT/logcat.txt" 2>&1
grep -E "RustyNES-thor|RustyNES-exp" "$OUT/logcat.txt" | tee "$OUT/thor-log.txt" | head -20 | tee -a "$OUT/steps.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime|RustyNES" "$OUT/logcat.txt" | head -80 > "$OUT/logcat-app.txt"
grep -E "RA status" "$OUT/logcat.txt" | tee -a "$OUT/steps.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "RESULT: crash"; else log "RESULT: no crash"; fi
if grep -q "am_anr" "$OUT/logcat.txt"; then log "RESULT: ANR"; grep "ANR in" "$OUT/logcat.txt" | head -3 | tee -a "$OUT/steps.txt"; else log "RESULT: no ANR"; fi
[ -n "${PID:-}" ] && log "RESULT: process alive" || log "RESULT: process NOT running"
exit 0
