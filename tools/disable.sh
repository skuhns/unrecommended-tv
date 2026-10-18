#!/usr/bin/env bash
# Disable every package in a debloat list (one package per line, # comments allowed). Packages are
# disabled for user 0, never uninstalled; undo with tools/restore.sh <same list>.
#
# Usage: TV_SERIAL=<ip:port> tools/disable.sh examples/<your-tv>/debloat.txt
#   (no adb on this Mac? TV_HOST=<ip> uses the Python client in tools/tv.py instead)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
CMDS=$(grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | awk '{print "pm disable-user --user 0 " $1}')
if [ -n "${TV_SERIAL:-}" ]; then
  # One command per adb call: chained pm commands can silently stop after the first.
  while read -r c; do echo "$ $c"; adb -s "$TV_SERIAL" shell "$c" </dev/null; done <<< "$CMDS"
  adb -s "$TV_SERIAL" shell pm list packages -d </dev/null
else
  PY=tools/venv/bin/python
  [ -x "$PY" ] || { echo "Set TV_SERIAL (adb), or set up the Python client: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
  { echo "$CMDS"; echo "pm list packages -d"; } | "$PY" -I tools/tv.py --batch
fi
