# Setting it up

About an hour. If you're using an AI assistant ([PROMPT.md](../PROMPT.md)), you only need to do
step 1 and the TV-side parts of step 2 yourself; it does the rest and tells you when to press buttons.

You need a computer (these steps are for a Mac; Windows and Linux work too) on the same network as
the TV, and the TV remote.

---

## 1. Turn on developer mode on the TV

1. **Settings → System → About** (on some TVs *Device Preferences → About*).
2. Scroll to **Android TV OS build** (or *Build*) and press OK on it **seven times**. You'll see
   "You are now a developer".
3. Back to **Settings → System → Developer options**:
   - Turn on **USB debugging**.
   - Turn on **Wireless debugging** if your TV has it (Android 11 and newer).

Nothing here changes how the TV works day to day.

## 2. Connect your computer to the TV

**Install the tools** (once), in Terminal:

```bash
brew install --cask android-platform-tools      # adb, the tool that talks to the TV
brew install openjdk@17 android-commandlinetools # only needed to build the launcher
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-34" "build-tools;34.0.0"
git clone <this repository's URL> ~/tv-vibehack && cd ~/tv-vibehack
```

**Pair, once.** On the TV: *Developer options → Wireless debugging → Pair device with pairing code*.
It shows an address like `192.168.1.50:37123` and a 6-digit code:

```bash
tools/connect.sh pair 192.168.1.50:37123 123456
```

**Connect** (each time you work on it) with the TV's address, the part before the colon:

```bash
tools/connect.sh 192.168.1.50
# → Connected. Use:  export TV_SERIAL=192.168.1.50:41234
export TV_SERIAL=192.168.1.50:41234
```

The TV asks "Allow debugging?" the first time: choose **Always allow**.

Older TVs without Wireless debugging: use `adb connect 192.168.1.50:5555` and `export TV_SERIAL=192.168.1.50:5555`.

**If it can't reach the TV** ("no route to host", timeouts) your Wi-Fi probably keeps devices apart
(common in apartment buildings, offices and mesh/guest networks). Either:

- **Phone hotspot:** put the TV and the computer on your phone's hotspot while you set up. The launcher
  keeps working when the TV goes back to your normal Wi-Fi; you only need the hotspot to make changes.
- **Tailscale** (free): install it on the TV (Play Store) and the computer, sign both into the same
  account, then use the TV's Tailscale address (100.x.y.z) with `tools/connect.sh`. Works from any
  network, any time. The TV must be on (not in standby) to connect.

## 3. Get a free search key (TMDB)

Movie and show search uses The Movie Database.

1. Create a free account at [themoviedb.org](https://www.themoviedb.org/signup).
2. Account → **Settings → API** → request an API key (choose "Personal", any description).
3. Copy the **API Read Access Token** (the long one starting `eyJ…`).
4. Put it in `launcher/local.properties` (create the file if needed; it is never committed):

```
tmdb.token=eyJhbGciOi…
```

Skip this and search still finds your apps, just not movies and shows.

## 4. Make your TV profile

Your settings live in a folder under `examples/`. Start from the template:

```bash
cp -r examples/_template examples/my-tv
```

Edit `examples/my-tv/config.xml` (every setting is explained in [CONFIG.md](CONFIG.md)):

- **Favourites:** up to six apps for the bottom row, and the apps for "More".
- **Weather:** your latitude and longitude (right-click a spot in Google Maps to copy them).
- **Game day:** your NFL team and your local channels, if you'll use an antenna.
- **Search country:** for where-to-watch.

To see which apps are on your TV: `adb -s $TV_SERIAL shell pm list packages -3` (apps you installed)
or `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER`.

## 5. Back up, then switch off the ads

```bash
mkdir -p backup
for x in "" -s -3 -d; do adb -s $TV_SERIAL shell pm list packages $x > backup/packages$x.txt; done
for ns in system secure global; do adb -s $TV_SERIAL shell settings list $ns > backup/settings-$ns.txt; done
```

Write the packages to switch off in `examples/my-tv/debloat.txt`, one per line (see the
[Hisense list](../examples/hisense-55u8qg/debloat.txt) for what's typical: the maker's ad/promo/content
apps, auto-installers, store demo mode). Then:

```bash
tools/disable.sh examples/my-tv/debloat.txt
```

**Google TV only:** the stock launcher always wins the Home button, so add these two lines at the end
of your list (the template has them):

```
com.google.android.tungsten.setupwraith
com.google.android.apps.tv.launcherx
```

Keep: anything for the mic/voice button, casting (Chromecast, AirPlay, DLNA), the TV's picture/sound/
input/tuner services and firmware updates. When unsure, leave it on.

Undo everything any time: `tools/restore.sh examples/my-tv/debloat.txt`.

## 6. Build and install

```bash
TV_PROFILE=examples/my-tv launcher/build-and-install.sh
```

It builds the app, installs it, makes it the Home screen, and grants what it needs (notification access
for "now playing", channel access for game day, and the picture service). Press Home on the remote.

Run the same command after any change to your profile.

## 7. Picture looks (optional, supported TVs)

The Night/Day/Sports looks drive your TV's own picture menu, so they need a `picture.json` for your
model. If one exists in `examples/` for your TV, copy it into your profile. Otherwise see
[PICTURE.md](PICTURE.md) to make one (an assistant can do it for you: it reads your TV's menus over adb).

Also worth doing once, on any TV (from the picture research, see the
[55U8QG example](../examples/hisense-55u8qg/picture-research.md)):

- Use a real picture mode, not **Energy Saving**.
- Turn off automatic picture/mode switching (*Content Type Auto Detection*, *Auto Picture Mode*), the
  light sensor and AI "enhancers".
- Set **HDMI format: Enhanced** for 4K HDR devices (consoles, Apple TV).

## 8. Nice extras

- **No screensaver.** The paintings already change, and LCD/mini-LED TVs don't burn in. Turn it off
  and set the TV to turn off after an hour idle:
  ```bash
  adb -s $TV_SERIAL shell settings put secure screensaver_enabled 0
  adb -s $TV_SERIAL shell settings put system screen_off_timeout 3600000
  adb -s $TV_SERIAL shell settings put secure sleep_timeout 3600000
  ```
- **Antenna:** plug it in, then on game day press the game label (or open Live TV) and choose **Scan**.
- **Show a specific painting:** `adb -s $TV_SERIAL shell am start -n com.gathin.tvhome/.MainActivity --es art_id <id>`
  (the id is in the artwork's artic.edu address).

Something not working? [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
