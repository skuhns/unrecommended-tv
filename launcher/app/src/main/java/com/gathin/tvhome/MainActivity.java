package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.KeyEvent;
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
 * Home: a row of favourite apps, a row for the rest of the regulars, a row of HDMI inputs, and an
 * "All apps" section that stays collapsed until asked for, over a rotating painting.
 */
public class MainActivity extends Activity {

    private static final long MINUTE_MS = 60_000L;
    private static final float TILE_REST_ALPHA = 0.45f;

    private ScrollView scroll;
    private RecyclerView front, tier, onNow, inputs, allApps;
    private TileAdapter frontAdapter, tierAdapter, onNowAdapter, inputsAdapter, allAdapter;
    private TvChannels channels;
    private TextView btnAllApps;
    private TextView greeting;
    private NowPlayingBar nowPlaying;
    private BackdropView backdrop;
    private AppLayout layout;
    private ArtRotator art;
    private GameDay gameDay;
    private Weather weather;
    /**
     * When the window gains focus (fresh start, or Home pressed while home) it gives focus to the first
     * focusable view, the Search button; start on the first favourite instead.
     */
    private boolean focusFavouritesOnWindowFocus = true;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && focusFavouritesOnWindowFocus) {
            focusFavouritesOnWindowFocus = false;
            front.post(() -> focusFirst(front));
        }
    }

    /** Move mode: the app being moved, its section, and the order to restore on Back. */
    private String movingPkg;
    private AppLayout.Section movingSection;
    private List<String> movingUndo;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            reloadOnNow();
            if (!getString(R.string.weather_lat).isEmpty()) weather.refresh();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        float d = getResources().getDisplayMetrics().density;

        layout = new AppLayout(this);
        scroll = findViewById(R.id.scroll);
        greeting = findViewById(R.id.greeting);
        front = findViewById(R.id.front);
        tier = findViewById(R.id.tier);
        onNow = findViewById(R.id.onNow);
        inputs = findViewById(R.id.inputs);
        allApps = findViewById(R.id.allApps);
        btnAllApps = findViewById(R.id.btnAllApps);

        // One tile size everywhere, sized so six fit across the screen.
        int tileW = (int) (128 * d), tileH = (int) (72 * d);

        // All six favourites fit across one screen, evenly spaced.
        frontAdapter = new TileAdapter(tileW, tileH, false);
        frontAdapter.setCentered(true);
        frontAdapter.setShowNames(false);
        setUpGrid(front, frontAdapter, AppLayout.FRONT_MAX);
        tierAdapter = new TileAdapter(tileW, tileH, true);
        setUpRow(tier, tierAdapter);
        onNowAdapter = new TileAdapter(tileW, tileH, true);
        setUpRow(onNow, onNowAdapter);
        channels = new TvChannels(this, getResources().getStringArray(R.array.live_channels));
        inputsAdapter = new TileAdapter(tileW, tileH, true);
        setUpRow(inputs, inputsAdapter);
        allAdapter = new TileAdapter(tileW, tileH, false);
        allAdapter.setCentered(true);
        setUpGrid(allApps, allAdapter, getResources().getInteger(R.integer.all_apps_columns));
        // App tiles stay faint until focused, so their bright brand colours don't fight the painting.
        for (TileAdapter a : new TileAdapter[]{frontAdapter, tierAdapter, allAdapter}) {
            a.setRestAlpha(TILE_REST_ALPHA);
            a.setFocusRing(false);   // focus shows as full opacity and a slight lift instead
        }

        btnAllApps.setOnClickListener(v -> setAllAppsShown(allApps.getVisibility() != View.VISIBLE));
        findViewById(R.id.btnSearch).setOnClickListener(v -> open(new Intent(this, SearchActivity.class)));
        View btnPicture = findViewById(R.id.btnPicture);
        btnPicture.setOnClickListener(v -> open(new Intent(this, PicturePanelActivity.class)));
        btnPicture.setVisibility(PictureConfig.get(this).enabled() ? View.VISIBLE : View.GONE);
        nowPlaying = new NowPlayingBar(this);

        // Background: a rotating painting.
        backdrop = findViewById(R.id.backdrop);
        TextView weatherText = findViewById(R.id.weather);
        weather = new Weather(getString(R.string.weather_lat), getString(R.string.weather_lon),
                getResources().getBoolean(R.bool.weather_fahrenheit), weatherText::setText);
        weatherText.setVisibility(getString(R.string.weather_lat).isEmpty() ? View.GONE : View.VISIBLE);

        View gameLabel = findViewById(R.id.gameLabel);
        TextView gameTitle = findViewById(R.id.gameTitle), gameDetail = findViewById(R.id.gameDetail);
        gameDay = new GameDay(this, getString(R.string.team), getString(R.string.team_name),
                getResources().getBoolean(R.bool.show_live_score),
                getResources().getStringArray(R.array.local_stations), game -> {
            gameLabel.setVisibility(game == null ? View.GONE : View.VISIBLE);
            if (game == null) return;
            gameTitle.setText(game.title);
            gameDetail.setText(game.detail);
            gameLabel.setFocusable(game.watch != null);
            gameLabel.setOnClickListener(game.watch == null ? null : v -> game.watch.run());
        });

        View artLabel = findViewById(R.id.artLabel);
        TextView artTitle = findViewById(R.id.artTitle), artByline = findViewById(R.id.artByline);
        art = new ArtRotator((image, title, byline) -> {
            backdrop.setWallpaper(image);
            artTitle.setText(title);
            artByline.setText(byline);
            artByline.setVisibility(byline.isEmpty() ? View.GONE : View.VISIBLE);
            artLabel.setVisibility(View.VISIBLE);
        });

        // The first screen is exactly one screen tall: clock, top bar, favourites. The rest is below.
        View firstScreen = findViewById(R.id.firstScreen);
        View content = findViewById(R.id.root);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = (b - t) - content.getPaddingTop();
            if (firstScreen.getMinimumHeight() != h) {
                firstScreen.setMinimumHeight(h);
                firstScreen.requestLayout();
            }
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
        reloadOnNow();
        updateGreeting();
        if (!getString(R.string.weather_lat).isEmpty()) weather.refresh();
        handler.postDelayed(minuteTick, MINUTE_MS);
        nowPlaying.start();
        art.start();
        if (!getString(R.string.team).isEmpty()) gameDay.start();
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
        art.stop();
        gameDay.stop();
    }

    /**
     * Home pressed while already home: back to the top. Also takes an Art Institute artwork id to show
     * right away: adb shell am start -n com.gathin.tvhome/.MainActivity --es art_id 143471
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String artId = intent.getStringExtra("art_id");
        if (artId != null) {
            art.showNow(artId);
            return;
        }
        setAllAppsShown(false);
        scroll.smoothScrollTo(0, 0);
        // onNewIntent runs while paused, where a focus change doesn't stick; do it once resumed.
        front.postDelayed(() -> focusFirst(front), 150);
        // If the window is (re)gaining focus it may put focus back on the Search button afterwards.
        focusFavouritesOnWindowFocus = true;
        front.postDelayed(() -> focusFavouritesOnWindowFocus = false, 1000);
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

    private final Map<String, TileAdapter.Tile> appTiles = new LinkedHashMap<>();

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        appTiles.clear();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || appTiles.containsKey(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            appTiles.put(pkg, new TileAdapter.Tile(pkg, label, banner, ri.loadIcon(pm),
                    () -> launchApp(cn, label), () -> showAppOptions(pkg)));
            labels.put(pkg, label);
        }
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        layout.sync(this, labels, hidden);
        showLayout();
    }

    private void showLayout() {
        frontAdapter.setTiles(pick(appTiles, layout.front));
        tierAdapter.setTiles(pick(appTiles, layout.tier));
        allAdapter.setTiles(pick(appTiles, layout.all));
        // A rebuilt row keeps its old scroll offset otherwise, hiding whatever now sits first.
        front.scrollToPosition(0);
        tier.scrollToPosition(0);
    }

    // ---- arranging apps --------------------------------------------------------------------------

    private RecyclerView viewFor(AppLayout.Section s) {
        return s == AppLayout.Section.FRONT ? front : s == AppLayout.Section.TIER ? tier : allApps;
    }

    private TileAdapter adapterFor(AppLayout.Section s) {
        return s == AppLayout.Section.FRONT ? frontAdapter : s == AppLayout.Section.TIER ? tierAdapter : allAdapter;
    }

    private static String sectionName(AppLayout.Section s) {
        return s == AppLayout.Section.FRONT ? "Big tiles" : s == AppLayout.Section.TIER ? "More" : "All apps";
    }

    /** Hold OK on an app: move it, send it to another section, or open its app info. */
    private void showAppOptions(String pkg) {
        TileAdapter.Tile tile = appTiles.get(pkg);
        AppLayout.Section here = layout.sectionOf(pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (layout.list(here).size() > 1) {
            labels.add("Move");
            actions.add(() -> startMoving(pkg));
        }
        for (AppLayout.Section to : AppLayout.Section.values()) {
            if (to == here) continue;
            labels.add("Move to " + sectionName(to));
            actions.add(() -> sendTo(pkg, to));
        }
        labels.add("App info");
        actions.add(() -> openAppInfo(pkg));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tile != null ? tile.label : pkg)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void sendTo(String pkg, AppLayout.Section to) {
        layout.moveTo(pkg, to);
        showLayout();
        if (to == AppLayout.Section.ALL) setAllAppsShown(true);
        focusApp(pkg);
    }

    private void focusApp(String pkg) {
        AppLayout.Section s = layout.sectionOf(pkg);
        RecyclerView rv = viewFor(s);
        int pos = layout.list(s).indexOf(pkg);
        if (pos < 0) return;
        rv.scrollToPosition(pos);
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
            if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    private void startMoving(String pkg) {
        movingPkg = pkg;
        movingSection = layout.sectionOf(pkg);
        movingUndo = new ArrayList<>(layout.list(movingSection));
        adapterFor(movingSection).setMoving(movingUndo.indexOf(pkg));
        focusApp(pkg);
        Toast.makeText(this, "Arrows move it · OK places it · Back cancels", Toast.LENGTH_LONG).show();
    }

    private void stopMoving(boolean keep) {
        AppLayout.Section s = movingSection;
        String pkg = movingPkg;
        if (!keep) {
            List<String> order = layout.list(s);
            order.clear();
            order.addAll(movingUndo);
        }
        layout.save();
        movingPkg = null;
        movingSection = null;
        movingUndo = null;
        adapterFor(s).setMoving(-1);
        showLayout();
        focusApp(pkg);
    }

    /** While moving, the arrows reorder instead of moving focus. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingPkg == null) return super.dispatchKeyEvent(e);
        int key = e.getKeyCode();
        boolean handled = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_BACK;
        if (!handled) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            stopMoving(true);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            stopMoving(false);
            return true;
        }
        List<String> order = layout.list(movingSection);
        int from = order.indexOf(movingPkg);
        int cols = movingSection == AppLayout.Section.ALL ? getResources().getInteger(R.integer.all_apps_columns) : 0;
        int to = from;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) to = from - 1;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) to = from + 1;
        else if (cols > 0 && key == KeyEvent.KEYCODE_DPAD_UP) to = from - cols;
        else if (cols > 0 && key == KeyEvent.KEYCODE_DPAD_DOWN) to = from + cols;
        if (to < 0 || to >= order.size() || to == from) return true;
        order.add(to, order.remove(from));
        adapterFor(movingSection).move(from, to);
        focusApp(movingPkg);
        return true;
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

    // ---- live TV ---------------------------------------------------------------------------------

    /** "On now": the configured antenna channels and what's on each, from the TV's program guide. */
    private void reloadOnNow() {
        if (getResources().getStringArray(R.array.live_channels).length == 0) {
            showOnNow(new ArrayList<>());
            return;
        }
        new Thread(() -> {
            List<TvChannels.Program> now = channels.onNow();
            runOnUiThread(() -> showOnNow(now));
        }).start();
    }

    private void showOnNow(List<TvChannels.Program> now) {
        List<TileAdapter.Tile> tiles = new ArrayList<>();
        for (TvChannels.Program p : now) {
            String label = p.title == null ? p.channel.label : p.channel.label + "\n" + p.title;
            tiles.add(new TileAdapter.Tile(label, null, null, () -> channels.tune(p.channel.id), null));
        }
        onNowAdapter.setTiles(tiles);
        int v = tiles.isEmpty() ? View.GONE : View.VISIBLE;
        onNow.setVisibility(v);
        findViewById(R.id.onNowLabel).setVisibility(v);
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
