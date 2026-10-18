package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything TV-specific about the Picture panel, read from res/raw/picture.json (a TV profile's
 * picture.json replaces it at build time; see examples/). The TV's own picture menu is operated
 * through accessibility, so this names that menu, its rows and on-screen ids, the order of picture
 * modes per signal type, the looks, and the recipe (settings) for each picture mode a look uses.
 * With no looks configured the Picture button is hidden.
 */
final class PictureConfig {

    static final class Look {
        final String name;
        final List<String> modes;

        Look(String name, List<String> modes) {
            this.name = name;
            this.modes = modes;
        }
    }

    // The TV's picture menu
    String pkg = "", openAction = "android.settings.DISPLAY_SETTINGS", menuTitle = "", modeRow = "", brightnessRow = "";
    List<String> titleIds = new ArrayList<>(), valueIds = new ArrayList<>();
    String pageTitleId = "";
    // Picture modes in the order Right steps through them, per signal type (SDR, HDR10, Dolby Vision…)
    final List<List<String>> lanes = new ArrayList<>();
    final List<Look> looks = new ArrayList<>();
    final Map<String, List<PictureService.Setting>> recipes = new LinkedHashMap<>();
    int recipeVersion = 1;
    // The remote button that opens the panel over any app (a vendor broadcast carrying the keycode)
    String panelBroadcast = "", panelExtra = "keycode";
    int panelKeycode = -1;

    private static PictureConfig instance;

    static synchronized PictureConfig get(Context ctx) {
        if (instance == null) instance = load(ctx.getApplicationContext());
        return instance;
    }

    boolean enabled() {
        return !looks.isEmpty() && !pkg.isEmpty();
    }

    private static PictureConfig load(Context ctx) {
        PictureConfig c = new PictureConfig();
        try (InputStream in = ctx.getResources().openRawResource(R.raw.picture)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONObject j = new JSONObject(buf.toString("UTF-8"));

            JSONObject menu = j.optJSONObject("menu");
            if (menu != null) {
                c.pkg = menu.optString("package");
                c.openAction = menu.optString("open_action", c.openAction);
                c.menuTitle = menu.optString("title");
                c.modeRow = menu.optString("mode_row");
                c.brightnessRow = menu.optString("brightness_row");
                c.titleIds = strings(menu.optJSONArray("row_title_ids"));
                c.valueIds = strings(menu.optJSONArray("row_value_ids"));
                c.pageTitleId = menu.optString("page_title_id");
            }
            JSONObject lanes = j.optJSONObject("mode_order");
            if (lanes != null) {
                for (java.util.Iterator<String> it = lanes.keys(); it.hasNext(); ) {
                    c.lanes.add(strings(lanes.getJSONArray(it.next())));
                }
            }
            JSONArray looks = j.optJSONArray("looks");
            if (looks != null) for (int i = 0; i < looks.length(); i++) {
                JSONObject l = looks.getJSONObject(i);
                c.looks.add(new Look(l.getString("name"), strings(l.getJSONArray("modes"))));
            }
            JSONObject recipes = j.optJSONObject("recipes");
            if (recipes != null) {
                for (java.util.Iterator<String> it = recipes.keys(); it.hasNext(); ) {
                    String mode = it.next();
                    JSONArray rows = recipes.getJSONArray(mode);
                    List<PictureService.Setting> list = new ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.getJSONObject(i);
                        Object v = r.get("value");
                        list.add(v instanceof Number
                                ? PictureService.Setting.number(r.getString("page"), r.getString("row"), ((Number) v).intValue())
                                : PictureService.Setting.choice(r.getString("page"), r.getString("row"), String.valueOf(v)));
                    }
                    c.recipes.put(mode, list);
                }
            }
            c.recipeVersion = j.optInt("recipe_version", 1);
            JSONObject button = j.optJSONObject("panel_button");
            if (button != null) {
                c.panelBroadcast = button.optString("broadcast");
                c.panelExtra = button.optString("extra", c.panelExtra);
                c.panelKeycode = button.optInt("keycode", -1);
            }
        } catch (Exception e) {
            android.util.Log.w("PictureConfig", "picture.json unreadable; Picture panel off", e);
            return new PictureConfig();
        }
        return c;
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    // ---- recipes and which modes have been tuned ---------------------------------------------------

    /** The recipe for {@code mode} if it still needs tuning ({@code force}: always), else null. */
    List<PictureService.Setting> recipeFor(SharedPreferences prefs, String mode, boolean force) {
        List<PictureService.Setting> r = null;
        for (Map.Entry<String, List<PictureService.Setting>> e : recipes.entrySet()) {
            if (PictureService.same(e.getKey(), mode)) r = e.getValue();
        }
        if (r == null) return null;
        return force || prefs.getInt(tunedKey(mode), 0) != recipeVersion ? r : null;
    }

    void markTuned(SharedPreferences prefs, String mode) {
        prefs.edit().putInt(tunedKey(mode), recipeVersion).apply();
    }

    private static String tunedKey(String mode) {
        return "tuned:" + PictureService.norm(mode);
    }
}
