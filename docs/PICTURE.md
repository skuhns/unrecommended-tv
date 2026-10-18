# Picture looks

The Picture panel gives one-press **looks** (for example *Night*, *Day*, *Sports & TV*) plus shortcuts
into the TV's Brightness and Picture Mode menus. A spare remote button can open it over anything.

## How it works, and why it's built this way

TV makers keep picture mode and backlight in their own system services. Apps can't call them, and
Android's own brightness setting usually doesn't touch the backlight. (On the Hisense, changing
`screen_brightness` did nothing visible; the settings provider is locked to system apps.)

What does work is the TV's **own picture menu**: the launcher opens it, reads the rows on screen
through an accessibility service ("Home picture control"), and presses the D-pad keys for you
(Android 13+ lets accessibility services do that). You'll see the TV's menu flash briefly.

Three things make it fast and safe:

- **Each look owns picture modes, and the TV saves settings per mode.** The first time a look lands on a
  mode it sets that mode's rows to the recipe (20–45 s, once). After that a look is only a mode change
  (1–3 s), because the brightness, gamma, motion etc. are still stored in that mode.
- **Settings are per signal type** (regular SDR, HDR10, Dolby Vision each have their own modes). A look
  lists one mode per type, and the panel picks the one the playing content offers. The remote button
  matters for this: the home screen is always SDR, so HDR and Dolby Vision modes can only be set
  while such content plays.
- **It only presses OK on a row it has just re-read and identified**, so it can't wander into
  something like *Reset*. Values are read back after every change.

Tip: if your TV has an "Apply picture settings: All sources / Current source" option, *All sources*
makes one tuning cover apps and HDMI inputs alike.

## picture.json

Your profile's `picture.json` describes your TV. Complete example: [`examples/hisense-55u8qg/picture.json`](../examples/hisense-55u8qg/picture.json).

```jsonc
{
  "menu": {
    "package": "com.vt.customsettings",            // app that draws the TV's picture menu
    "open_action": "android.settings.DISPLAY_SETTINGS",  // how to open it
    "title": "Display & Picture",                  // the menu's title text
    "mode_row": "Picture Mode",                    // row whose value is the picture mode
    "brightness_row": "Brightness",                // row that opens the brightness page (and the slider in it)
    "row_title_ids": ["android:id/title", "com.vt.customsettings:id/title"],
    "row_value_ids": ["com.vt.customsettings:id/indicator", "android:id/summary"],
    "page_title_id": "com.vt.customsettings:id/decor_title"
  },
  "mode_order": {                                  // the order Right steps through modes, per signal type
    "sdr": ["Theater Night", "FILMMAKER MODE", "Vivid", "..."],
    "hdr": ["HDR Theater", "..."],
    "dolby_vision": ["Dolby Vision IQ", "Dolby Vision Dark", "..."]
  },
  "looks": [                                       // buttons, in order; modes in order of preference
    { "name": "Night", "modes": ["Dolby Vision Dark", "HDR Theater", "Theater Night"] }
  ],
  "recipe_version": 1,                             // bump after editing recipes: modes get re-tuned on next use
  "recipes": {                                     // per picture mode: page, row, value
    "Theater Night": [
      { "page": "Brightness", "row": "Brightness", "value": 40 },     // number = slider
      { "page": "Brightness", "row": "Gamma", "value": "BT1886" }     // text = choice (cycled with Right)
    ]
  },
  "panel_button": {                                // optional: remote button that opens the panel anywhere
    "broadcast": "com.hisense.INTERCEPT_BUTTON_OPERATING", "extra": "keycode", "keycode": 172
  }
}
```

Rows a TV doesn't have are skipped, not fatal, so one recipe can be shared between similar models.
Keep recipes in the order the rows appear in the menu (it walks down the page).

## Making one for your TV (an assistant can do all of this over adb)

1. **Find the menu.** Open the picture menu and dump the screen:
   ```bash
   adb -s $TV_SERIAL shell am start -a android.settings.DISPLAY_SETTINGS
   adb -s $TV_SERIAL shell uiautomator dump /sdcard/ui.xml && adb -s $TV_SERIAL shell cat /sdcard/ui.xml
   ```
   Note the package, the title text, and the `resource-id`s of row titles, values and the page title.
   Some TVs use different ids for rows that open a submenu; list both.
2. **Record the modes.** Focus the Picture Mode row and press Right repeatedly (`input keyevent DPAD_RIGHT`),
   dumping the value each time, until it wraps. Do it once with regular content, once during HDR10
   (a YouTube 4K HDR video works) and once during Dolby Vision (Apple TV+ shows are Dolby Vision;
   Netflix and Max need their 4K plans).
3. **Walk each page** (Brightness, Color, Clarity…) and write down every row and value. This is also
   your audit of the current settings.
4. **Research the best settings for the exact model** (RTINGS, professional reviews, owner forums) per
   use: dark-room movies, bright room, sports, and per signal type. Write it up like
   [`picture-research.md`](../examples/hisense-55u8qg/picture-research.md), with how sure you are of each.
5. **Write the looks and recipes**, then test each look on the TV and read the rows back.

## The remote button

Remote keys usually never reach accessibility services on TVs (the system handles them first, and
buttons like *Netflix* are wired to their app deep in the firmware). Hisense broadcasts every key
press as `com.hisense.INTERCEPT_BUTTON_OPERATING` with the keycode, which the launcher listens for.
Pick a button that does nothing else; on the Hisense remote that's the small TV/Guide button (172).

To find what a button sends: `adb -s $TV_SERIAL shell getevent -lt` while pressing it (raw), and
`adb -s $TV_SERIAL shell logcat | grep -i keycode` (Android keycode). To look for a vendor key
broadcast: `adb -s $TV_SERIAL shell dumpsys activity broadcasts history | grep -i keycode`.
