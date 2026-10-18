#!/usr/bin/env bash
# Build the launcher, install it on the TV, make it the Home screen and grant what it needs.
#
# Usage:   TV_SERIAL=<ip:port> [TV_PROFILE=examples/<your-tv>] launcher/build-and-install.sh
#   TV_SERIAL   the TV as adb knows it (tools/connect.sh prints it; or see `adb devices`)
#   TV_PROFILE  a folder with your config.xml and picture.json (see examples/ and docs/CONFIG.md);
#               without one, the defaults in launcher/app/src/main/res are used.
# The stock launcher stays installed; on Google TV, disable it with your profile's debloat list.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/launcher"
: "${TV_SERIAL:?set TV_SERIAL to the adb address of the TV (ip:port), e.g. TV_SERIAL=192.168.1.50:5555 $0}"

# A profile's files override the defaults through the debug build's resources (git-ignored copy).
rm -rf app/src/debug/res
if [ -n "${TV_PROFILE:-}" ]; then
  case "$TV_PROFILE" in /*) PROFILE="$TV_PROFILE" ;; *) PROFILE="$ROOT/$TV_PROFILE" ;; esac
  [ -d "$PROFILE" ] || { echo "No profile folder at $PROFILE"; exit 1; }
  mkdir -p app/src/debug/res/values app/src/debug/res/raw
  [ -f "$PROFILE/config.xml" ] && cp "$PROFILE/config.xml" app/src/debug/res/values/config.xml
  [ -f "$PROFILE/picture.json" ] && cp "$PROFILE/picture.json" app/src/debug/res/raw/picture.json
  echo "Using profile: $PROFILE"
fi

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
