package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Picture panel's buttons and status, shared by the home screen (PicturePanelActivity) and the
 * overlay the picture service shows over any app when the remote's Netflix button is pressed.
 */
final class PicturePanel {

    /** What differs between showing the panel as a screen and as an overlay. */
    interface Host {
        /** Start an activity (the TV's picture menu, accessibility settings). */
        void start(Intent intent) throws ActivityNotFoundException;

        /** About to hand the screen to the TV's picture menu. */
        void beforeMenu();

        /** A look finished; the panel may already be gone. */
        void afterLook(String summary);

        void close();
    }

    private final Context ctx;
    private final Host host;
    private final SharedPreferences prefs;
    private final TextView status;
    private final LinearLayout presets;

    PicturePanel(Context ctx, View root, Host host) {
        this.ctx = ctx;
        this.host = host;
        prefs = ctx.getSharedPreferences("picture", Context.MODE_PRIVATE);
        Looks.migrate(prefs);
        status = root.findViewById(R.id.status);
        presets = root.findViewById(R.id.presets);

        for (Looks.Look look : Looks.ALL) {
            TextView b = (TextView) LayoutInflater.from(ctx).inflate(R.layout.item_panel_button, presets, false);
            b.setOnClickListener(v -> applyLook(look, false));
            b.setOnLongClickListener(v -> {
                applyLook(look, true);
                return true;
            });
            presets.addView(b);
        }
        root.findViewById(R.id.openBrightness).setOnClickListener(v -> openBrightness());
        root.findViewById(R.id.openModes).setOnClickListener(v -> {
            // The menu opens on the Picture Mode row.
            host.beforeMenu();
            if (openMenu()) host.close();
        });
        ((PanelFrame) root).setOnBack(host::close);

        render(null);
        presets.getChildAt(Math.max(0, activeIndex())).requestFocus();
    }

    /** Switch to the look's mode for whatever is playing; tune that mode the first time (or when held). */
    private void applyLook(Looks.Look look, boolean retune) {
        PictureService s = service();
        if (s == null || s.isBusy()) return;
        status.setText(look.name + "…");
        host.beforeMenu();
        if (!openMenu()) return;
        s.applyLook(look.modes, mode -> Looks.recipeFor(prefs, mode, retune), r -> {
            SharedPreferences.Editor e = prefs.edit();
            if (r.error == null) {
                e.putString("look", look.name);
                if (r.mode != null && r.recipeRan) Looks.markTuned(prefs, r.mode);
            }
            if (r.mode != null) e.putString("mode", r.mode);
            e.apply();
            render(r);
            host.afterLook(r.error == null ? look.name + " · " + r.mode : "Couldn't finish: " + r.error);
        });
    }

    /** The service walks the menu to the Brightness slider and leaves it open; without it, open the menu. */
    private void openBrightness() {
        PictureService s = PictureService.get();
        host.beforeMenu();
        if (s == null) {
            if (openMenu()) host.close();
            return;
        }
        if (s.isBusy() || !openMenu()) return;
        s.openBrightness(r -> {
            if (r.error != null) render(r);
            else host.close();
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
            host.start(i);
            return true;
        } catch (ActivityNotFoundException e) {
            status.setText("Not available on this TV");
            return false;
        }
    }

    private int activeIndex() {
        String active = prefs.getString("look", null);
        for (int i = 0; i < Looks.ALL.length; i++) if (Looks.ALL[i].name.equals(active)) return i;
        return -1;
    }

    private void render(PictureService.Result r) {
        int active = activeIndex();
        for (int i = 0; i < Looks.ALL.length; i++) {
            ((TextView) presets.getChildAt(i)).setText(i == active ? Looks.ALL[i].name + "   ✓" : Looks.ALL[i].name);
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
