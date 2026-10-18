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

import java.util.Arrays;
import java.util.List;

/** Picture quick panel: three looks, plus shortcuts into the TV's own Brightness and Picture Mode menus. */
public class PicturePanelActivity extends Activity {

    /** A look picks the first mode the current content offers (Dolby Vision, HDR and SDR lists differ). */
    private static final class Look {
        final String name;
        final List<String> modes;

        Look(String name, String... modes) {
            this.name = name;
            this.modes = Arrays.asList(modes);
        }
    }

    private static final Look[] LOOKS = {
            new Look("Night", "Dolby Vision Dark", "HDR Theater", "Theater Night", "FILMMAKER MODE"),
            new Look("Day", "Dolby Vision Bright", "Dolby Vision IQ", "HDR Day", "Theater Day", "Standard"),
            new Look("Sports & TV", "Dolby Vision Bright", "HDR Standard", "Sports", "Standard"),
    };

    private SharedPreferences prefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picture);
        prefs = getSharedPreferences("picture", MODE_PRIVATE);
        status = findViewById(R.id.status);

        LinearLayout presets = findViewById(R.id.presets);
        for (Look look : LOOKS) {
            TextView b = (TextView) LayoutInflater.from(this).inflate(R.layout.item_panel_button, presets, false);
            b.setText(look.name);
            b.setOnClickListener(v -> applyLook(look));
            presets.addView(b);
        }
        findViewById(R.id.openBrightness).setOnClickListener(v -> openBrightness());
        findViewById(R.id.openModes).setOnClickListener(v -> {
            // The menu opens on the Picture Mode row.
            if (openMenu()) finish();
        });

        renderStatus(null);
        presets.getChildAt(0).requestFocus();
    }

    private void applyLook(Look look) {
        PictureService s = service();
        if (s == null || s.isBusy()) return;
        status.setText(look.name + "…");
        if (openMenu()) s.setMode(look.modes, this::applied);
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
            if (r.error != null) renderStatus(r.error);
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

    private void applied(PictureService.Result r) {
        if (r.mode != null) prefs.edit().putString("mode", r.mode).apply();
        renderStatus(r.error);
    }

    private void renderStatus(String error) {
        String mode = prefs.getString("mode", null);
        String s = mode != null ? "Now: " + mode : "Pick a look";
        if (error != null) s += "\n" + ("busy".equals(error) ? "Still working…" : "Couldn't finish: " + error);
        status.setText(s);
    }
}
