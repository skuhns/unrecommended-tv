package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.gathin.tvhome.PictureService.Setting.choice;
import static com.gathin.tvhome.PictureService.Setting.number;

/**
 * Picture quick panel: three looks, plus shortcuts into the TV's own Brightness and Picture Mode menus.
 *
 * Each look owns one picture mode, and Hisense stores brightness, gamma, motion etc. per mode. So the
 * first press tunes that mode to the look's recipe (slow: the menu walks every row), and after that a
 * look is just a mode switch. Hold OK on a look to re-apply its recipe. Recipes follow the 55U8QG
 * research report (settings per scenario), with all AI processing off.
 */
public class PicturePanelActivity extends Activity {

    private static final String B = "Brightness", C = "Color", L = "Clarity";

    /** Off in every look: AI and "enhancer" processing (report: leave off for good sources; owner: no AI). */
    private static List<PictureService.Setting> brightnessPage(String dimming, String peak, int backlight, String gamma) {
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

    private static List<PictureService.Setting> colorPage() {
        // Native colour space stretches SDR to the panel's wide gamut (Standard ships that way, plus Color 55).
        return Arrays.asList(
                number(C, "Color", 50),
                choice(C, "Color Temperature", "Warm1"),
                choice(C, "Color Space", "Auto"),
                choice(C, "Dynamic Color Enhancer", "Off"));
    }

    private static List<PictureService.Setting> recipe(List<PictureService.Setting>... parts) {
        List<PictureService.Setting> all = new ArrayList<>();
        for (List<PictureService.Setting> p : parts) all.addAll(p);
        return all;
    }

    private static final class Look {
        final String name;
        final List<String> modes;
        final List<PictureService.Setting> recipe;

        Look(String name, List<String> modes, List<PictureService.Setting> recipe) {
            this.name = name;
            this.modes = modes;
            this.recipe = recipe;
        }
    }

    /** Bump when a recipe changes so the next press re-applies it. */
    private static final int RECIPE_VERSION = 2;

    private static final Look[] LOOKS = {
            // Dark room, movies. Theater Night: most accurate SDR mode out of the box (RTINGS).
            new Look("Night", Arrays.asList("Theater Night", "FILMMAKER MODE"), recipe(
                    brightnessPage("Medium", "Medium", 40, "BT1886"),
                    colorPage(),
                    Arrays.asList(
                            number(L, "Sharpness", 0),
                            choice(L, "AI Super Resolution", "Off"),
                            choice(L, "Noise Reduction", "Off"),
                            choice(L, "MPEG Noise Reduction", "Off"),
                            choice(L, "Motion Enhancement", "Film"),
                            choice(L, "Motion Clearness", "Off")))),
            // Lit room, any content: brighter backlight, lighter gamma so shadows stay visible.
            new Look("Day", Arrays.asList("Theater Day", "Standard"), recipe(
                    brightnessPage("High", "High", 85, "2.2"),
                    colorPage(),
                    Arrays.asList(
                            number(L, "Sharpness", 0),
                            choice(L, "AI Super Resolution", "Off"),
                            choice(L, "Noise Reduction", "Off"),
                            choice(L, "MPEG Noise Reduction", "Off"),
                            choice(L, "Motion Enhancement", "Film"),
                            choice(L, "Motion Clearness", "Off")))),
            // Live sport and broadcast TV: Standard tamed (not the oversaturated Sports mode), more blur
            // reduction for 60fps, no de-judder, a little sharpening and noise reduction for soft broadcasts.
            new Look("Sports & TV", Arrays.asList("Standard"), recipe(
                    brightnessPage("High", "High", 90, "2.2"),
                    colorPage(),
                    Arrays.asList(
                            number(L, "Sharpness", 5),
                            choice(L, "AI Super Resolution", "Off"),
                            choice(L, "Noise Reduction", "Low"),
                            choice(L, "MPEG Noise Reduction", "Low"),
                            choice(L, "Motion Enhancement", "Custom"),
                            number(L, "Judder Reduction", 0),
                            number(L, "Blur Reduction", 6),
                            choice(L, "Motion Clearness", "Off")))),
    };

    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout presets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picture);
        prefs = getSharedPreferences("picture", MODE_PRIVATE);
        status = findViewById(R.id.status);

        presets = findViewById(R.id.presets);
        for (Look look : LOOKS) {
            TextView b = (TextView) LayoutInflater.from(this).inflate(R.layout.item_panel_button, presets, false);
            b.setOnClickListener(v -> applyLook(look, !recipeApplied(look)));
            b.setOnLongClickListener(v -> {
                applyLook(look, true);
                return true;
            });
            presets.addView(b);
        }
        findViewById(R.id.openBrightness).setOnClickListener(v -> openBrightness());
        findViewById(R.id.openModes).setOnClickListener(v -> {
            // The menu opens on the Picture Mode row.
            if (openMenu()) finish();
        });

        render(null);
        presets.getChildAt(Math.max(0, activeIndex())).requestFocus();
    }

    private boolean recipeApplied(Look look) {
        return prefs.getInt("recipe:" + look.name, 0) == RECIPE_VERSION;
    }

    private void applyLook(Look look, boolean fullRecipe) {
        PictureService s = service();
        if (s == null || s.isBusy()) return;
        status.setText(fullRecipe ? "Tuning " + look.name + " (first time, about a minute)…" : look.name + "…");
        if (!openMenu()) return;
        PictureService.Done done = r -> {
            SharedPreferences.Editor e = prefs.edit();
            if (r.error == null) {
                e.putString("look", look.name);
                if (fullRecipe) e.putInt("recipe:" + look.name, RECIPE_VERSION);
            }
            if (r.mode != null) e.putString("mode", r.mode);
            e.apply();
            render(r);
        };
        if (fullRecipe) s.applyRecipe(look.modes, look.recipe, done);
        else s.setMode(look.modes, done);
    }

    /** The service walks the menu to the Brightness slider and leaves it open; without it, open the menu. */
    private void openBrightness() {
        PictureService s = PictureService.get();
        if (s == null) {
            if (openMenu()) finish();
            return;
        }
        if (s.isBusy() || !openMenu()) return;
        s.openBrightness(r -> {
            if (r.error != null) render(r);
            else finish();
        });
    }

    private PictureService service() {
        PictureService s = PictureService.get();
        if (s == null) {
            status.setText(R.string.picture_service_off);
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        return s;
    }

    private boolean openMenu() {
        return open(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            status.setText("Not available on this TV");
            return false;
        }
    }

    private int activeIndex() {
        String active = prefs.getString("look", null);
        for (int i = 0; i < LOOKS.length; i++) if (LOOKS[i].name.equals(active)) return i;
        return -1;
    }

    private void render(PictureService.Result r) {
        int active = activeIndex();
        for (int i = 0; i < LOOKS.length; i++) {
            ((TextView) presets.getChildAt(i)).setText(i == active ? LOOKS[i].name + "   ✓" : LOOKS[i].name);
        }
        String mode = prefs.getString("mode", null);
        StringBuilder s = new StringBuilder(mode != null ? "Now: " + mode : "Pick a look");
        if (r != null && r.error != null) {
            s.append("\n").append("busy".equals(r.error) ? "Still working…" : "Couldn't finish: " + r.error);
        } else if (r != null && !r.changed.isEmpty()) {
            s.append("\nTuned ").append(r.changed.size()).append(r.changed.size() == 1 ? " setting" : " settings");
        }
        if (r != null && !r.skipped.isEmpty()) s.append("\nSkipped: ").append(String.join(", ", r.skipped));
        status.setText(s.toString());
    }
}
