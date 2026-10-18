#!/usr/bin/env bash
# Undo a debloat list: re-enable every package in it and make the stock launcher Home again.
#
# Usage: TV_SERIAL=<ip:port> tools/restore.sh examples/<your-tv>/debloat.txt [stock launcher package]
#   Google TV's launcher is com.google.android.apps.tv.launcherx (the default here);
#   older Android TV uses com.google.android.tvlauncher.
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: restore.sh <debloat list> [stock launcher package]}"
STOCK="${2:-com.google.android.apps.tv.launcherx}"
CMDS=$(grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | awk '{print "pm enable " $1}')
CMDS="$CMDS
pm enable $STOCK
cmd package set-home-activity --user 0 $STOCK
settings put global window_animation_scale 1.0
settings put global transition_animation_scale 1.0
settings put global animator_duration_scale 1.0"
if [ -n "${TV_SERIAL:-}" ]; then
  while read -r c; do echo "$ $c"; adb -s "$TV_SERIAL" shell "$c" </dev/null; done <<< "$CMDS"
else
  { echo "$CMDS"; echo "pm list packages -d"; } | tools/venv/bin/python -I tools/tv.py --batch
fi
