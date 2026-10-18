# Contributing

Thanks for helping more TVs lose their ads. Three kinds of contributions are especially welcome.

## 1. Add your TV profile

Every brand ships different bloat. If you ran the prompt (or did it by hand) on a TV that isn't in
`examples/`, add it:

1. Copy `examples/_template` to `examples/<brand>-<model>/` and fill it in: `debloat.txt` (one
   package per line, grouped with a comment, and a note on anything non-obvious), `config.xml`, and if
   you got picture looks working, `picture.json` and your `picture-research.md`. A `picture.json` that
   drives a new brand's picture menu is especially welcome.
2. Put the exact model name, Android version and firmware build in the header so people can tell
   whether it matches theirs: `adb shell getprop ro.product.model`, `ro.build.version.release` and
   `ro.build.display.id` (the retail model is often only on the sticker).
3. Only list packages you have actually disabled and lived with for a few days. Note anything that
   broke and what you re-enabled.
4. Add a row to `examples/README.md`.
5. If your TV's stock launcher has a different package name, say so in the header;
   `tools/restore.sh` takes it as its second argument.

Never list packages to *uninstall*. Everything in this repo disables with `pm disable-user`, which
is reversible from the TV's own settings if the tools are gone.

## 2. Improve the launcher

The launcher lives in `launcher/` and is deliberately small: plain Java, AndroidX RecyclerView and
DynamicAnimation, no Kotlin, no Compose. It should stay light on 1 GB TVs:

- Network only for the art, weather, search and schedules, and only while the home screen is visible.
- No video backgrounds, no heavy libraries, no background polling.
- Anything that depends on a specific TV or person goes in a profile (`config.xml`, `picture.json`),
  not in code.
- Keep D-pad navigation deterministic. Test Up/Down/Left/Right from every edge.
- Verify on a real TV, not only the emulator: inject remote keys and take a screenshot,
  `adb shell input keyevent DPAD_DOWN` then `adb exec-out screencap -p > s.png`. Include a screenshot
  in your pull request.

Build with `TV_SERIAL=<ip:port> TV_PROFILE=examples/<your-tv> launcher/build-and-install.sh` (JDK 17, Android SDK platform 34 and
build-tools 34.0.0; on macOS `brew install openjdk@17 android-commandlinetools` then
`sdkmanager "platforms;android-34" "build-tools;34.0.0"`).

Ideas that fit the direction: picture.json profiles for more brands, other sports leagues for game
day, per-TV icon packs for apps without banners, localisation.
Ideas that don't: recommendation rows, content feeds, ads, telemetry.

## 3. Improve the prompt and tools

`PROMPT.md` is what most people will actually use. If your assistant got stuck somewhere, the fix
usually belongs in the prompt as one extra sentence, not in a script. Keep it brand-neutral; put
brand-specific knowledge in `examples/`.

`tools/tv.py` and `tools/b64push.py` exist because the stock `adb` is blocked on recent macOS. If
you find a cleaner workaround, open an issue first.

## Pull requests

- One topic per PR. Small is fine.
- Say which TV you tested on.
- No new dependencies in the launcher without discussing it in an issue.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
