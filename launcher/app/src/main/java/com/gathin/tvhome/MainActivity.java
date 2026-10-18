package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home: big tiles for favourite apps, a small side-scrolling row for the rest of the regulars,
 * a row of HDMI inputs, and an "All apps" section that stays collapsed until asked for.
 */
public class MainActivity extends Activity {

    private static final long MINUTE_MS = 60_000L;

    private ScrollView scroll;
    private RecyclerView front, tier, inputs, allApps;
    private TileAdapter frontAdapter, tierAdapter, inputsAdapter, allAdapter;
    private TextView btnAllApps;
    private TextView greeting;
    private NowPlayingBar nowPlaying;
    private BackdropView backdrop;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        float d = getResources().getDisplayMetrics().density;

        scroll = findViewById(R.id.scroll);
        greeting = findViewById(R.id.greeting);
        front = findViewById(R.id.front);
        tier = findViewById(R.id.tier);
        inputs = findViewById(R.id.inputs);
        allApps = findViewById(R.id.allApps);
        btnAllApps = findViewById(R.id.btnAllApps);

        int bigW = (int) (256 * d), bigH = (int) (144 * d);
        int smallW = (int) (152 * d), smallH = (int) (86 * d);

        frontAdapter = new TileAdapter(bigW, bigH, false);
        frontAdapter.setWrap(true);
        setUpGrid(front, frontAdapter, getResources().getInteger(R.integer.front_columns));
        tierAdapter = new TileAdapter(smallW, smallH, true);
        setUpRow(tier, tierAdapter);
        inputsAdapter = new TileAdapter(smallW, smallH, true);
        setUpRow(inputs, inputsAdapter);
        allAdapter = new TileAdapter(smallW, smallH, false);
        setUpGrid(allApps, allAdapter, getResources().getInteger(R.integer.all_apps_columns));

        btnAllApps.setOnClickListener(v -> setAllAppsShown(allApps.getVisibility() != View.VISIBLE));
        findViewById(R.id.btnSearch).setOnClickListener(v -> open(new Intent(this, SearchActivity.class)));
        findViewById(R.id.btnPicture).setOnClickListener(v -> open(new Intent(this, PicturePanelActivity.class)));
        nowPlaying = new NowPlayingBar(this);

        // Background: album art while music plays, plus a glow in the focused app's colour.
        backdrop = findViewById(R.id.backdrop);
        nowPlaying.setArtListener(backdrop::setArt);
        TileAdapter.FocusListener glow = (tile, card) -> backdrop.glowFor(tile.banner != null ? tile.banner : tile.icon, card);
        frontAdapter.setFocusListener(glow);
        tierAdapter.setFocusListener(glow);
        allAdapter.setFocusListener(glow);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean appTile = newF != null && newF.getId() == R.id.card && inputs.findContainingViewHolder(newF) == null;
            if (!appTile) backdrop.glowFor(null, null);
        });
    }

    private void setUpGrid(RecyclerView rv, TileAdapter adapter, int columns) {
        rv.setLayoutManager(new GridLayoutManager(this, columns));
        rv.setNestedScrollingEnabled(false);
        rv.setItemAnimator(null);
        rv.setAdapter(adapter);
    }

    private void setUpRow(RecyclerView rv, TileAdapter adapter) {
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setNestedScrollingEnabled(false);
        rv.setItemAnimator(null);
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        reloadInputs();
        updateGreeting();
        handler.postDelayed(minuteTick, MINUTE_MS);
        nowPlaying.start();
        backdrop.resume();
        // The window may hand initial focus to the scroll view itself; move it onto a tile.
        front.post(() -> {
            View f = getCurrentFocus();
            if (f == null || f == scroll) focusFirst(front);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        nowPlaying.stop();
        backdrop.pause();
    }

    /** Home pressed while already home: back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setAllAppsShown(false);
        scroll.smoothScrollTo(0, 0);
        focusFirst(front);
    }

    @Override
    public void onBackPressed() {
        if (allApps.getVisibility() == View.VISIBLE) {
            setAllAppsShown(false);
            btnAllApps.requestFocus();
        }
    }

    private void setAllAppsShown(boolean shown) {
        allApps.setVisibility(shown ? View.VISIBLE : View.GONE);
        btnAllApps.setText(shown ? R.string.hide_apps : R.string.all_apps);
        if (shown) allApps.post(() -> focusFirst(allApps));
    }

    private void focusFirst(RecyclerView rv) {
        View first = rv.getChildCount() > 0 ? rv.getChildAt(0).findViewById(R.id.card) : null;
        if (first != null) {
            first.requestFocus();
            return;
        }
        // Tiles not laid out yet (first launch): focus once they are.
        rv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (rv.getChildCount() == 0) return;
                rv.removeOnLayoutChangeListener(this);
                rv.getChildAt(0).findViewById(R.id.card).requestFocus();
            }
        });
    }

    // ---- apps ------------------------------------------------------------------------------------

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, TileAdapter.Tile> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            byPkg.put(pkg, new TileAdapter.Tile(label, banner, ri.loadIcon(pm),
                    () -> launchApp(cn, label), () -> openAppInfo(pkg)));
        }

        List<String> frontPkgs = Arrays.asList(getResources().getStringArray(R.array.front_apps));
        List<String> tierPkgs = Arrays.asList(getResources().getStringArray(R.array.tier_apps));
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));

        frontAdapter.setTiles(pick(byPkg, frontPkgs));
        tierAdapter.setTiles(pick(byPkg, tierPkgs));

        List<TileAdapter.Tile> rest = new ArrayList<>();
        for (Map.Entry<String, TileAdapter.Tile> e : byPkg.entrySet()) {
            String pkg = e.getKey();
            if (!frontPkgs.contains(pkg) && !tierPkgs.contains(pkg) && !hidden.contains(pkg)) rest.add(e.getValue());
        }
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
        allAdapter.setTiles(rest);
    }

    private static List<TileAdapter.Tile> pick(Map<String, TileAdapter.Tile> byPkg, List<String> pkgs) {
        List<TileAdapter.Tile> out = new ArrayList<>();
        for (String p : pkgs) {
            TileAdapter.Tile t = byPkg.get(p);
            if (t != null) out.add(t);
        }
        return out;
    }

    private void launchApp(ComponentName cn, String label) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(String pkg) {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    // ---- inputs ----------------------------------------------------------------------------------

    private void reloadInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TileAdapter.Tile> tiles = new ArrayList<>();
        if (tim != null) {
            // HDMI ports first, then DisplayPort (USB-C video in). Analog AV is left out.
            List<TvInputInfo> list = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                int type = info.getType();
                if (info.isPassthroughInput()
                        && (type == TvInputInfo.TYPE_HDMI || type == TvInputInfo.TYPE_DISPLAY_PORT)) list.add(info);
            }
            Collections.sort(list, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            Drawable icon = getDrawable(R.drawable.ic_input);
            for (TvInputInfo info : list) {
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                if (info.getType() == TvInputInfo.TYPE_HDMI && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                String id = info.getId();
                tiles.add(new TileAdapter.Tile(label, null, icon,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")),
                        null));
            }
        }
        inputsAdapter.setTiles(tiles);
    }

    // ---- misc ------------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }
}
