# <Brand Model>

Copy this folder to `examples/<your-tv>/` and fill it in (the setup prompt in PROMPT.md does this with you).

| File | What it holds |
|---|---|
| `config.xml` | Your apps, weather location, team and local channels ([docs/CONFIG.md](../../docs/CONFIG.md)). |
| `debloat.txt` | Ad and promo packages to switch off for this TV. |
| `picture.json` | How to drive this TV's picture menu, and your looks ([docs/PICTURE.md](../../docs/PICTURE.md)). |
| `picture-research.md` | Best picture settings for this model, with sources. |

Build: `TV_SERIAL=<ip:port> TV_PROFILE=examples/<your-tv> launcher/build-and-install.sh`

## What this TV needed
<connection notes, quirks, what was changed and why, how to undo>
