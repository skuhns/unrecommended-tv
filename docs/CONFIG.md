# config.xml: every setting

Your settings go in `examples/<your-tv>/config.xml` (copy [`examples/_template/config.xml`](../examples/_template/config.xml)).
Build with `TV_PROFILE=examples/<your-tv> launcher/build-and-install.sh`; your file replaces the
defaults in `launcher/app/src/main/res/values/config.xml`, so keep every setting in it.

## Apps

| Setting | What it does |
|---|---|
| `front_apps` | Favourites: up to six apps along the bottom of the first screen, in order. |
| `tier_apps` | The "More" row below the fold, in order. Good for Settings, Live TV, rarely used apps. |
| `hidden_apps` | Never shown anywhere (e.g. apps you've kept enabled but never open). |
| `all_apps_columns` | Columns in "All apps" (6 lines up with the favourites). |

Every app not listed lands in **All apps**. Apps that aren't installed are skipped. You can also
rearrange on the TV: **hold OK on an app** → Move / Move to Favourites, More or All apps. Those changes
are saved on the TV and take over from this file for that app.

### Common package names

| App | Package |
|---|---|
| Netflix | `com.netflix.ninja` |
| YouTube | `com.google.android.youtube.tv` |
| YouTube Music | `com.google.android.youtube.tvmusic` |
| Prime Video | `com.amazon.amazonvideo.livingroom` |
| Disney+ | `com.disney.disneyplus` |
| Max | `com.wbd.stream` |
| Hulu | `com.hulu.livingroomplus` |
| Peacock | `com.peacocktv.peacockandroid` |
| Apple TV | `com.apple.atve.androidtv.appletv` |
| Paramount+ | `com.cbs.ott` |
| Spotify | `com.spotify.tv.android` |
| Plex | `com.plexapp.android` |
| Settings | `com.android.tv.settings` |
| Play Store | `com.android.vending` |
| Live TV (Hisense) | `com.vt.tv.tvcenter` |

Not listed? `adb -s $TV_SERIAL shell pm list packages | grep -i <name>`.

## Search

| Setting | What it does |
|---|---|
| `tmdb_region` | Country for "where to watch" (ISO code: `US`, `GB`, `CA`, `DE`…). |

The TMDB key goes in `launcher/local.properties` as `tmdb.token=…` (never committed). See [SETUP.md](SETUP.md#3-get-a-free-search-key-tmdb).

## Weather

| Setting | What it does |
|---|---|
| `weather_lat`, `weather_lon` | Your location (e.g. `41.88`, `-87.63`). Leave `weather_lat` empty to hide the weather. |
| `weather_fahrenheit` | `true` for °F, `false` for °C. |

## Game day

| Setting | What it does |
|---|---|
| `team` | ESPN's NFL abbreviation, lower case: `chi`, `gb`, `kc`, `phi`, `dal`, `sf`, `ne`… Empty turns game day off. |
| `team_name` | How it's written on the label ("Bears"). |
| `show_live_score` | `true` shows the score during the game; `false` (default) keeps it spoiler-free. |
| `local_stations` | Your over-the-air affiliates as `network\|channel\|label`, e.g. `fox\|32.1\|FOX 32`. Find yours at [rabbitears.info](https://www.rabbitears.info) or in the TV's channel list after a scan. |

A press on the game label tunes the antenna channel for CBS/FOX/NBC/ABC games, opens Prime Video,
Peacock or Netflix for games streaming there, or (no antenna channels yet) opens the TV's channel scan.

## Picture looks

Not in this file: picture looks live in your profile's `picture.json`. See [PICTURE.md](PICTURE.md).
