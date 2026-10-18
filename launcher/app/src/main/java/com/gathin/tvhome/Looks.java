package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.gathin.tvhome.PictureService.Setting.choice;
import static com.gathin.tvhome.PictureService.Setting.number;

/**
 * The picture looks and the recipe for each picture mode they use (55U8QG research report, all AI
 * processing off).
 *
 * The TV keeps separate settings per signal type (regular SDR, HDR10, Dolby Vision) and per picture
 * mode. A look lists one mode per signal type; whichever the playing content offers wins. The first
 * time a mode is used it's tuned to its recipe; after that a look is just a mode switch.
 */
final class Looks {

    static final class Look {
        final String name;
        final List<String> modes;

        Look(String name, String... modes) {
            this.name = name;
            this.modes = Arrays.asList(modes);
        }
    }

    static final Look[] ALL = {
            new Look("Night", "Dolby Vision Dark", "HDR Theater", "Theater Night", "FILMMAKER MODE"),
            new Look("Day", "Dolby Vision IQ", "HDR Standard", "Theater Day", "Standard"),
            new Look("Sports & TV", "Dolby Vision IQ", "HDR Standard", "Standard"),
    };

    /** Bump when a recipe changes so each mode gets re-tuned on its next use. */
    private static final int VERSION = 3;

    private static final String B = "Brightness", C = "Color", L = "Clarity";
    private static final Map<String, List<PictureService.Setting>> RECIPES = new HashMap<>();

    static {
        // Regular (SDR). Night: dark-room movies; Day: lit room; Standard: sport and broadcast TV,
        // tamed (the Sports mode itself is oversaturated and unmeasured).
        RECIPES.put("Theater Night", join(sdrBrightness("Medium", "Medium", 40, "BT1886"), color(true), film(0)));
        RECIPES.put("FILMMAKER MODE", RECIPES.get("Theater Night"));
        RECIPES.put("Theater Day", join(sdrBrightness("High", "High", 85, "2.2"), color(true), film(0)));
        RECIPES.put("Standard", join(sdrBrightness("High", "High", 90, "2.2"), color(true), Arrays.asList(
                number(L, "Sharpness", 5),
                choice(L, "AI Super Resolution", "Off"),
                choice(L, "Noise Reduction", "Low"),
                choice(L, "MPEG Noise Reduction", "Low"),
                choice(L, "Motion Enhancement", "Custom"),
                number(L, "Judder Reduction", 0),
                number(L, "Blur Reduction", 6),
                choice(L, "Motion Clearness", "Off"))));
        // HDR10: full backlight, Peak High (the only setting that tracks HDR correctly), tone mapping
        // off at night for accuracy, on is fine in a bright room.
        RECIPES.put("HDR Theater", join(hdrBrightness("Off"), color(true), film(0)));
        RECIPES.put("HDR Standard", join(hdrBrightness("On"), color(true), film(0)));
        // Dolby Vision: the content steers the picture; keep Peak High and the processing off.
        // (Color Space is locked for Dolby Vision on this TV.)
        List<PictureService.Setting> dv = join(Arrays.asList(
                choice(B, "Local Dimming", "High"),
                choice(B, "Peak Brightness", "High"),
                number(B, "Brightness", 100),
                choice(B, "Dark Detail", "Off"),
                choice(B, "AI Contrast", "Off")), color(false), Arrays.asList(
                number(L, "Sharpness", 0),
                choice(L, "AI Super Resolution", "Off"),
                choice(L, "Noise Reduction", "Off"),
                choice(L, "MPEG Noise Reduction", "Off"),
                choice(L, "Precision Detail", "Off")));
        RECIPES.put("Dolby Vision Dark", dv);
        RECIPES.put("Dolby Vision IQ", dv);
    }

    private static List<PictureService.Setting> sdrBrightness(String dimming, String peak, int backlight, String gamma) {
        return Arrays.asList(
                choice(B, "Local Dimming", dimming),
                choice(B, "Peak Brightness", peak),
                number(B, "Brightness", backlight),
                number(B, "Contrast", 50),
                number(B, "Black Level", 0),
                choice(B, "Dark Detail", "Off"),
                choice(B, "Gamma", gamma),
                choice(B, "AI Contrast", "Off"),
                choice(B, "HDR Enhancer", "Off"));
    }

    private static List<PictureService.Setting> hdrBrightness(String toneMapping) {
        return Arrays.asList(
                choice(B, "Local Dimming", "High"),
                choice(B, "Peak Brightness", "High"),
                number(B, "Brightness", 100),
                number(B, "Contrast", 50),
                number(B, "Black Level", 0),
                choice(B, "Dark Detail", "Off"),
                choice(B, "Gamma", "ST2084"),
                choice(B, "AI Contrast", "Off"),
                choice(B, "Dynamic Tone Mapping", toneMapping));
    }

    /** Native colour space stretches content to the panel's wide gamut (Standard ships that way). */
    private static List<PictureService.Setting> color(boolean colorSpace) {
        List<PictureService.Setting> out = new ArrayList<>(Arrays.asList(
                number(C, "Color", 50),
                choice(C, "Color Temperature", "Warm1")));
        if (colorSpace) out.add(choice(C, "Color Space", "Auto"));
        out.add(choice(C, "Dynamic Color Enhancer", "Off"));
        return out;
    }

    private static List<PictureService.Setting> film(int sharpness) {
        return Arrays.asList(
                number(L, "Sharpness", sharpness),
                choice(L, "AI Super Resolution", "Off"),
                choice(L, "Noise Reduction", "Off"),
                choice(L, "MPEG Noise Reduction", "Off"),
                choice(L, "Motion Enhancement", "Film"),
                choice(L, "Motion Clearness", "Off"),
                choice(L, "Precision Detail", "Off"));
    }

    @SafeVarargs
    private static List<PictureService.Setting> join(List<PictureService.Setting>... parts) {
        List<PictureService.Setting> all = new ArrayList<>();
        for (List<PictureService.Setting> p : parts) all.addAll(p);
        return all;
    }

    /** The recipe for {@code mode} if it still needs tuning ({@code force}: always), else null. */
    static List<PictureService.Setting> recipeFor(SharedPreferences prefs, String mode, boolean force) {
        List<PictureService.Setting> r = null;
        for (Map.Entry<String, List<PictureService.Setting>> e : RECIPES.entrySet()) {
            if (PictureService.same(e.getKey(), mode)) r = e.getValue();
        }
        if (r == null) return null;
        return force || prefs.getInt(tunedKey(mode), 0) != VERSION ? r : null;
    }

    static void markTuned(SharedPreferences prefs, String mode) {
        prefs.edit().putInt(tunedKey(mode), VERSION).apply();
    }

    /**
     * Modes tuned before recipes were per mode (the SDR looks), and the HDR Theater and Dolby Vision
     * Dark lanes tuned by hand on 18 Oct 2026, count as tuned.
     */
    static void migrate(SharedPreferences prefs) {
        if (prefs.getInt("recipe:Night", 0) < 2 || prefs.getBoolean("migrated:3", false)) return;
        for (String m : new String[]{"Theater Night", "Theater Day", "Standard", "HDR Theater", "Dolby Vision Dark"}) {
            markTuned(prefs, m);
        }
        prefs.edit().putBoolean("migrated:3", true).apply();
    }

    private static String tunedKey(String mode) {
        return "tuned:" + PictureService.norm(mode);
    }
}
