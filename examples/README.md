# TV profiles

One folder per TV setup. Each holds everything specific to that TV and its owner, so the launcher
itself stays generic. Build with `TV_PROFILE=examples/<folder> launcher/build-and-install.sh`.

| Folder | TV | Picture looks | Notes |
|---|---|---|---|
| [`_template/`](_template/) | Start here | — | Copy to `examples/<your-tv>/` and fill in. |
| [`hisense-55u8qg/`](hisense-55u8qg/) | Hisense 55U8QG, Google TV, Android 14 | Yes (SDR, HDR10, Dolby Vision) | Chicago, Bears, Spotify. Other Hisense Google TVs are likely close. |
| [`xiaomi-mi-tv-4a/`](xiaomi-mi-tv-4a/) | Xiaomi Mi TV 4A, Android TV 10 | No | Debloat list only (PatchWall, Mi analytics). |

To add yours, see [CONTRIBUTING.md](../CONTRIBUTING.md).
