# Troubleshooting

Everything here went wrong at least once.

## Connecting

- **"No route to host" / timeouts, but the TV is on the same Wi-Fi.** The network keeps devices apart
  (client isolation: apartment and office Wi-Fi, mesh and guest networks). Use a phone hotspot for setup,
  or Tailscale (see [SETUP.md](SETUP.md#2-connect-your-computer-to-the-tv)).
- **macOS: `adb connect` says "No route to host" while other tools reach the TV.** macOS Local Network
  privacy is blocking adb. Allow your terminal app in System Settings → Privacy & Security → Local
  Network, or use the Python client: `python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`,
  then `TV_HOST=<ip> tools/venv/bin/python -I tools/tv.py --probe`.
- **The port stopped working.** Wireless debugging picks a new port whenever it restarts (and after the
  network changes). `tools/connect.sh <ip>` finds it again.
- **Can't connect at all after the TV was off.** In low-power standby the TV leaves the network. Turn it
  on. (Settings → System → Power & Energy → Energy Modes → *Networked standby* keeps it reachable.)
- **Pairing fails immediately.** Pairing codes expire in about a minute; open the popup again.

## Home button and launcher

- **Home still opens Google TV's home screen.** Google TV's launcher declares a higher Home priority than
  any other app, so `set-home-activity` and the default-home setting are ignored. Disable
  `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith` (which re-enables
  it). The mic button, Ambient mode and the Input button keep working without it.
- **A random app (YouTube…) is open after installing.** Installing briefly removes the launcher and
  Android shows the last-used app. The install script presses Home afterwards if home was showing.
- **No on-screen keyboard after switching launchers** (seen on Xiaomi): the default keyboard pointed at
  one that isn't installed. `adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`.

## Picture panel

- **The panel opens Accessibility settings / "Picture control is off".** Android turns accessibility
  services off when an app is force-stopped (and sometimes doesn't restart them after an update). Re-run
  `build-and-install.sh`, or turn on "Home picture control" in Settings → Accessibility (Android may first
  require *Allow restricted settings* in the launcher's App info, because it was sideloaded).
- **A look says a row was skipped.** That row doesn't exist in this mode or on this TV (normal for e.g.
  HDR-only rows in SDR). Only a problem if it's a row you care about: check its exact name with
  `uiautomator dump`.
- **"Couldn't finish: row not found".** The menu changed (firmware update?) or a name in `picture.json`
  doesn't match the screen. Dump the menu and compare.
- **Picture looks don't change HDR/Dolby Vision.** The home screen is always SDR. Open the panel with the
  remote button while HDR or Dolby Vision content plays.
- **A streaming app plays in SDR.** Many services only send 4K HDR / Dolby Vision on their top plan (Max,
  Netflix). Apple TV+ always does.
- **Turning on the TV's settings changed other settings.** On the Hisense, cycling through picture modes by
  hand switched *Apply picture settings* and changed a mode's brightness. Check after experimenting.

## Now playing, search, game day

- **The banner is stuck on an old song.** Some apps swap media sessions without announcing it; the banner
  re-checks every 3 seconds while home is visible.
- **"Watch on" opens the app but not the show.** Most apps don't accept a search from outside. YouTube
  does (lands on results), Apple TV opens its search page; the rest open their home screen.
- **An app button shows a Play Store "update required" screen.** That app needs updating; not the launcher.
- **The game label opens Live TV on an HDMI input.** Older builds did; now it opens the antenna input,
  which offers *Scan* when there are no channels.
- **The game label doesn't tune the channel after scanning.** Check your `local_stations` numbers against
  the TV's channel list (`32.1` and `32-1` both work).

## Art

- **No painting appears.** The museum's search only pages through its first 1,000 results (page 11+ is
  refused), and it needs an internet connection. `adb logcat -s ArtRotator` shows failures.

## Undo

`tools/restore.sh examples/<your-tv>/debloat.txt` re-enables everything and restores the stock Home screen.
To remove the launcher: `adb shell pm uninstall com.gathin.tvhome`.
