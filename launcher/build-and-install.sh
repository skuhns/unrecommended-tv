#!/usr/bin/env bash
# Build the launcher APK, install it on the TV and make it the Home screen.
# The stock launcher stays installed (only the default changes); see README.
#
# Usage: TV_SERIAL=172.20.10.12:33291 launcher/build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_SERIAL:?set TV_SERIAL to the adb serial (ip:port from 'adb devices'), e.g. TV_SERIAL=192.168.1.50:5555 $0}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
# Installing briefly removes the launcher, so Android shows the last-used app instead. Note whether
# home was showing, and if so bring it back afterwards (never interrupt something else).
TOP=$(adb -s "$TV_SERIAL" shell dumpsys activity activities | grep -m1 topResumedActivity || true)
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$TV_SERIAL" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
# Game day: read the antenna channel list (the TV's own scan) to tune the right channel.
adb -s "$TV_SERIAL" shell pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS || true
# Now-playing banner: notification access lets it read media sessions.
adb -s "$TV_SERIAL" shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
# Picture panel: accessibility service that operates the TV's picture menu (kept alongside any others).
SVC=com.gathin.tvhome/com.gathin.tvhome.PictureService
CUR=$(adb -s "$TV_SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; ""|null) CUR="$SVC" ;; *) CUR="$CUR:$SVC" ;; esac
# Clear then set: after an update Android keeps the setting but doesn't always rebind the service.
OTHERS=$(printf '%s' "$CUR" | tr ':' '\n' | { grep -v "^$SVC$" || true; } | paste -sd: -)
adb -s "$TV_SERIAL" shell settings put secure enabled_accessibility_services "${OTHERS:-\"\"}"
sleep 1
adb -s "$TV_SERIAL" shell settings put secure enabled_accessibility_services "$CUR"
adb -s "$TV_SERIAL" shell settings put secure accessibility_enabled 1
case "$TOP" in *com.gathin.tvhome*) sleep 1; adb -s "$TV_SERIAL" shell input keyevent HOME ;; esac
echo "Installed. Press Home on the remote to see it."
