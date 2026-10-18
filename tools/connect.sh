#!/usr/bin/env bash
# Pair with and connect to the TV over Wireless debugging, and print the address to use as TV_SERIAL.
#
# On the TV: Settings → System → Developer options → Wireless debugging → On.
#   First time:  tools/connect.sh pair <ip:port from "Pair device with pairing code"> <6-digit code>
#   Then:        tools/connect.sh <tv ip>
# Wireless debugging picks a new port whenever it restarts, so this finds it: by the TV's network
# announcement on the same Wi-Fi, or (over Tailscale, where that doesn't travel) by trying ports.
set -euo pipefail
if [ "${1:-}" = "pair" ]; then
  adb pair "${2:?ip:port from the pairing popup}" "${3:?6-digit code}"
  exit
fi
IP="${1:?usage: tools/connect.sh <tv ip>   (or: tools/connect.sh pair <ip:port> <code>)}"

PORT=$(adb mdns services 2>/dev/null | awk -v ip="$IP" '/_adb-tls-connect/ && index($3, ip":") == 1 {split($3, a, ":"); print a[2]; exit}')
if [ -z "$PORT" ]; then
  echo "Looking for the TV's debugging port on $IP (takes about a minute)…"
  PORTS=$(python3 -I - "$IP" <<'PY'
import asyncio, sys
host, found = sys.argv[1], []
async def probe(p, sem):
    async with sem:
        try:
            _, w = await asyncio.wait_for(asyncio.open_connection(host, p), 2.0)
            found.append(p); w.close()
        except Exception:
            pass
async def main():
    sem = asyncio.Semaphore(600)
    await asyncio.gather(*(probe(p, sem) for p in range(30000, 50001)))
asyncio.run(main())
print(" ".join(map(str, sorted(found))))
PY
)
  for p in $PORTS; do
    if adb connect "$IP:$p" 2>&1 | grep -q '^connected'; then
      if adb -s "$IP:$p" shell echo ok 2>/dev/null | grep -q ok; then PORT=$p; break; fi
      adb disconnect "$IP:$p" >/dev/null 2>&1 || true
    fi
  done
else
  adb connect "$IP:$PORT" >/dev/null
fi
[ -n "$PORT" ] || { echo "Couldn't find it. Is Wireless debugging on, and have you paired (tools/connect.sh pair …)?"; exit 1; }
echo "Connected. Use:  export TV_SERIAL=$IP:$PORT"
