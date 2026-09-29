#!/bin/sh
# Installs the debug APK on a running emulator, walks through every tab and a few actions,
# and fails if the app crashes. Used by CI; needs adb on the PATH.
set -u
PKG=com.personalai.assistant
APK=$(ls "$1"/*.apk | head -1)

# Taps the first on-screen element whose text is exactly $1.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | head -1 |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  if [ -z "$bounds" ]; then echo "  (no \"$1\" on screen)"; return; fi
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 3
}

step() { echo "== $1"; }

adb install -r "$APK"
adb logcat -c

step "first launch"
adb shell am start -W -n $PKG/.ui.MainActivity; sleep 8
for t in Assistant Calls Memory Activity Settings; do step "tab $t"; tap "$t"; done
step "scroll settings"
adb shell input swipe 300 1500 300 300 300; sleep 2
adb shell input swipe 300 1500 300 300 300; sleep 2
adb shell input swipe 300 1500 300 300 300; sleep 2

step "grant permissions and relaunch"
for p in READ_CONTACTS CALL_PHONE READ_CALL_LOG SEND_SMS READ_CALENDAR WRITE_CALENDAR RECORD_AUDIO POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null
done
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.ui.MainActivity; sleep 8
for t in Calls Memory Activity Settings Assistant; do step "tab $t"; tap "$t"; done

step "send a message without an API key"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'class="android.widget.EditText"[^/]*bounds="[^"]*"' | head -1
adb shell input keyevent KEYCODE_ESCAPE
tap "Calls"; tap "Assistant"

step "rotate"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 4
adb shell settings put system user_rotation 0; sleep 4

step "background and return"
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am start -W -n $PKG/.ui.MainActivity; sleep 5

adb logcat -d -b crash > crash.txt
echo "===== crash buffer ====="
cat crash.txt
echo "===== app log ====="
pid=$(adb shell pidof $PKG)
adb logcat -d | grep -E "AndroidRuntime|FATAL|$PKG|SQLCipher|sqlcipher" | tail -150
if grep -q "FATAL EXCEPTION" crash.txt || [ -z "$pid" ]; then
  echo "::error::The app crashed or isn't running."
  exit 1
fi
echo "No crashes."
