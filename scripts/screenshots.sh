#!/usr/bin/env bash
# Runs inside the Android emulator job: installs uChoose, fakes a location,
# and captures the home, wheel and result screens into docs/screenshots/.
set -u
PKG=com.uchoose.app
OUT=docs/screenshots
LON=-80.8431   # Charlotte, NC (demo location)
LAT=35.2271
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
adb shell settings put secure location_mode 3

# Let the freshly booted emulator settle, then turn off its home-screen app
# (it tends to freeze on CI and cover everything with a "not responding" popup)
sleep 30
for launcher in $(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep / | cut -d/ -f1 | sort -u); do
  [ "$launcher" != "$PKG" ] && adb shell pm disable-user --user 0 "$launcher" || true
done
close_popups() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }

# Keep the screen on for the whole run
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

# Clean status bar for screenshots
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1900
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

# Keep feeding the fake GPS position in the background
( while true; do adb emu geo fix $LON $LAT >/dev/null 2>&1; sleep 2; done ) &
GEO=$!
sleep 6

dump() {
  adb shell rm -f /sdcard/ui.xml
  timeout 15 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null
}

# Prints "x y" of the center of the first node whose text matches exactly
find_text() {
  dump | python3 -c '
import re, sys
xml = sys.stdin.read()
want = sys.argv[1]
for m in re.finditer(r"<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    if m.group(1) == want:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

wait_for() {  # wait_for TEXT SECONDS
  for _ in $(seq 1 "$2"); do
    close_popups
    [ -n "$(find_text "$1")" ] && return 0
    sleep 1
  done
  echo "Timed out waiting for: $1"; return 1
}

tap() { read -r x y <<< "$(find_text "$1")"; echo "tap $1 at $x,$y"; adb shell input tap "$x" "$y"; }
shot() { close_popups; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "saved $1"; }

adb shell am start -n $PKG/.MainActivity
wait_for "uChoose" 30
sleep 8   # let the background pre-load finish
shot home

tap "SIT DOWN"
sleep 10   # let the loading spinner finish before reading the screen
if wait_for "SPIN" 20; then
  sleep 2
  shot wheel
  # Press and hold a slice to the right of the center to show the "hide" popup
  read -r sx sy <<< "$(find_text "SPIN")"
  adb shell input swipe $((sx + 300)) "$sy" $((sx + 300)) "$sy" 1500
  if wait_for "Hide this place?" 10; then
    shot hide
    tap "CANCEL"
    sleep 1
  fi
  tap "SPIN"
  wait_for "TONIGHT YOU DINE AT" 30 && sleep 1 && shot result

  # Back to home and try the coffee category too
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input keyevent KEYCODE_BACK; sleep 2
  tap "COFFEE & CAFÉS"
  sleep 10
  wait_for "SPIN" 20 && sleep 2 && shot coffee
else
  shot debug-wheel
  dump > "$OUT/debug-ui.xml"
  adb logcat -d | tail -200 > "$OUT/debug-logcat.txt"
fi

kill $GEO 2>/dev/null || true
ls -la "$OUT"
