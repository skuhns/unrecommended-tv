# Hisense 55U8QG (Google TV, Android 14)

A complete profile: a 2025 Hisense U8 mini-LED, 55", set up in Chicago for a Bears fan who listens on
Spotify and watches mostly streaming. Other Hisense Google TVs (U7, U8, U9, recent A and Q series) use
the same picture menu, so `picture.json` is a good starting point; check the mode names on yours.

| File | |
|---|---|
| [`config.xml`](config.xml) | Apps, Chicago weather, Bears game day, Chicago antenna channels (FOX 32, CBS 2, NBC 5, ABC 7, WGN 9, ION) for game day and "On now", *Sports & TV* when tuning. |
| [`picture.json`](picture.json) | Hisense picture-menu automation; Night / Day / Sports & TV for SDR, HDR10 and Dolby Vision. |
| [`picture-research.md`](picture-research.md) | Best-settings research for this model (17 reviews, RTINGS, forums), with confidence ratings. |
| [`debloat.txt`](debloat.txt) | What was switched off. |

```bash
TV_SERIAL=<ip:port> TV_PROFILE=examples/hisense-55u8qg launcher/build-and-install.sh
```

## What this TV needed

**Identifying it.** Android reports only "SmartTV 4K FFM" (device `wutaishan`, MediaTek `mt5877`); the
model is on the back sticker. The 55" uses a different panel (IPS-type, ~1,000 zones) from the 65" most
reviews tested, which changes the local-dimming advice.

**Connecting.** The building's Wi-Fi isolates devices, so setup ran over a phone hotspot and then
Tailscale. Wireless debugging needs pairing (Android 14) and changes port when it restarts;
`tools/connect.sh` finds it. In "Low" standby the TV drops off the network.

**Home button.** Google TV's `launcherx` outranks every other Home app; it and `setupwraith` are disabled.
Mic/Assistant, the Input button and Ambient mode keep working.

**Debloat.** VIDAA free channels, the Hisense content provider for Google's home, auto-installers,
store mode, Play Games, Hisense apps (HiShow, AI Sense, browser, media center, Alexa, AIoT).
RAM used by the home screen dropped from ~210 MB (Google's launcher) to ~80 MB.

**Settings audit (vs the research).** Already right: HDMI Format *Enhanced* on all inputs, light sensor,
AI energy saving and Intelligent Scene off, Instant Game Response *Auto*. Changed: **Content Type Auto
Detection → Off** (it switched modes by itself). Left: FreeSync off (no console), standby energy *Low*.
Screensaver off, auto-off after 1 hour.

**Picture lanes found** (each with its own modes and settings):

| Signal | Modes on this TV (Right-press order) | Was | Now |
|---|---|---|---|
| SDR | Theater Night, FILMMAKER, Vivid, Standard, Sports, Energy Saving, Theater Day | Theater Night | per look |
| HDR10 | HDR Theater, FILMMAKER, IMAX, HDR Vivid, HDR Standard, HDR Sports, HDR Energy Saving | **HDR Energy Saving** | HDR Theater, tone mapping off, sharpness 0 |
| Dolby Vision | DV IQ, DV Dark, DV Custom, DV Game (no "Bright") | DV IQ | DV Dark; Precision Detail off |

**Looks** (all AI processing off; see the research for why):

| | Dolby Vision | HDR10 | SDR |
|---|---|---|---|
| Night | DV Dark | HDR Theater (tone mapping off) | Theater Night: brightness 40, BT1886, dimming Medium, Film motion |
| Day | DV IQ | HDR Standard (tone mapping on) | Theater Day: brightness 85, gamma 2.2, dimming High |
| Sports & TV | DV IQ | HDR Standard | Standard: brightness 90, 2.2, Custom motion (blur 6, judder 0), color tamed (Standard ships Color 55 + Native gamut) |

**Remote button.** The **TV/Guide button (172)** opens the panel over anything. The Netflix button
can't be used: its launch lives in MediaTek's core TV agent, which also runs the picture database.

**Quirks.**
- Hisense's picture menu is a floating window plus a tooltip window from the same package; row titles
  use `android:id/title`, submenu rows `com.vt.customsettings:id/title`.
- Cycling picture modes by hand once flipped *Apply picture settings* and changed a mode's brightness.
- Dolby Vision's Color Space is locked.
- Max sends SDR on non-top plans; Apple TV+ was the reliable Dolby Vision test.
- Channels can only be scanned from the antenna source; the game label opens it ("Scan").

- Scan found ~120 channels, plus NextGen (-NG) duplicates. Classic CBS 2.1, FOX 32.1 and WGN 9.1 were
  "Weak or no signal" indoors; their -NG versions play and carry the guide. ABC 7 and ION have no -NG.
- Live TV has no channel editor (no skip/hide), and apps can't hide channels; only Favorites.

**Undo:** `tools/restore.sh examples/hisense-55u8qg/debloat.txt`.
