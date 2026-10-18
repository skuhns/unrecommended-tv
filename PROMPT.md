# The setup prompt

Paste everything below the line into Claude Code (or another coding assistant that can run commands)
from inside this repository, on a computer on the same network as the TV. First do step 1 of
[docs/SETUP.md](docs/SETUP.md) on the TV (developer mode, Wireless debugging on). Keep the remote handy:
the assistant will ask you to press a few buttons and answer questions.

---

Set up this repository's launcher on my Android TV / Google TV, end to end, for me. Read README.md,
docs/SETUP.md, docs/CONFIG.md, docs/PICTURE.md and docs/TROUBLESHOOTING.md first and follow them;
reuse tools/ and launcher/ rather than writing new versions. Work on the real TV and verify every step
by reading the screen (`uiautomator dump`) or taking a screenshot (`screencap`) and looking at it.

**How to work with me**
- Ask me before anything that depends on my taste or could change how I use the TV: which apps, what to
  disable, picture choices. Use checklists with recommendations, and keep questions short.
- Explain what you're doing in plain words, and keep me posted while long steps run.
- Before sending remote key presses, check the TV isn't in use (top activity and whether media is
  playing). If I'm using it, wait or ask. Never press keys blind: confirm where the focus is first.
- Never uninstall system apps; disable them (`pm disable-user --user 0`), one `pm` command per adb call.
- Don't force-stop the launcher: that switches off its accessibility service. Reinstall instead.
- Streaming apps block screenshots during video; read the screen with `uiautomator dump` instead.

**1. Connect.** Pair and connect with `tools/connect.sh` (tell me when to open the pairing popup and read
me what to type). If the TV can't be reached on our Wi-Fi, explain client isolation and offer a phone
hotspot or Tailscale. Keep using `TV_SERIAL` for everything.

**2. Identify and back up.** Get the exact model: `getprop` often only shows a generic name, so also read
Settings → About and ask me for the model on the sticker or box. Save package lists and all settings to
`backup/` before changing anything.

**3. Interview me.** Ask, as checklists where possible:
- Which apps I use, my six favourites (in order) for the bottom row, what goes in "More", what to hide.
- My music app (for the now-playing banner).
- My location (for the weather; city is enough) and country (for where-to-watch).
- Sports: my NFL team, whether I have or will get an antenna, and if so my local network channels.
- Whether I use the mic button, casting (AirPlay/Chromecast), HDMI devices or game consoles.
- My room: dark at night, bright by day? Do I want AI picture "enhancements" (default: off)?

**4. Research my TV.** Find the best picture settings for my exact model (professional reviews, RTINGS
and its comments, calibrators, the manual, owner forums). Note when reviewers tested a different size or
panel. Write `examples/<my-tv>/picture-research.md` with settings per scenario (dark-room movies, bright
room, sports/TV, gaming) and per signal type (SDR, HDR10, Dolby Vision), each with how sure you are and
why. Summarize the key points for me.

**5. Remove the ads.** List the installed packages and sort them: maker telemetry, ad/promo/content
services, auto-installers, demo modes, preloaded apps, streaming apps. Recommend what to disable as a
checklist; keep voice/mic, casting, and the TV's picture/sound/input/tuner and update services. On Google
TV also disable `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`
(the stock launcher outranks every other Home app). Write the list to `examples/<my-tv>/debloat.txt`
with comments, then apply it with `tools/disable.sh`.

**6. Search key.** Walk me through getting a free TMDB API read token (docs/SETUP.md step 3) and put it in
`launcher/local.properties` as `tmdb.token=…`. Never commit it.

**7. My profile.** Copy `examples/_template` to `examples/<my-tv>/` and fill in `config.xml` from my
answers (check every package name exists on the TV). Build and install with
`TV_PROFILE=examples/<my-tv> launcher/build-and-install.sh`. Then check on the TV: Home opens the
launcher, the first favourite has focus, a painting shows with its label, weather shows, the
now-playing banner reacts to my music app, search finds a show and offers my apps, and the game label
appears on game day.

**8. One-time TV settings.** Using the research, audit the TV's picture, display, power and input
settings by reading the menus (a table of current vs recommended). Ask before changing anything, then
fix the one-time ones (e.g. not Energy Saving, automatic picture-mode switching off, light sensor off,
HDMI Enhanced format for 4K HDR devices). Recommend turning off the screensaver (the art rotates; LCD
and mini-LED don't burn in) with auto-off after an hour.

**9. Picture looks** (if the TV's picture menu can be driven; see docs/PICTURE.md). Build
`examples/<my-tv>/picture.json`: the menu's package, titles and on-screen ids; the picture-mode order for
SDR, HDR10 (play a YouTube 4K HDR video) and Dolby Vision (ask me to start a Dolby Vision title; Apple
TV+ always is); then Night / Day / Sports & TV looks with one mode per signal type and a recipe per mode
from the research. Rebuild, run each look once, and read every row back to confirm. Then find a remote
button that does nothing else and wire it to open the panel over any app (docs/PICTURE.md, "The remote
button"); test it over a playing HDR video.

**10. Hand over.** Write `examples/<my-tv>/README.md`: what this TV needed, what was changed (with
before/after), the recipes and why, anything left to do (e.g. the antenna scan), and how to undo it.
Show me the final home screen and offer to commit the profile.
