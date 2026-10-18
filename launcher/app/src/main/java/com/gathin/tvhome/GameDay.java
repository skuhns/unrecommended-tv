package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The team's next game, from ESPN's public schedule feed, shown on game day only, and how to watch
 * it: the local antenna channel for network games (CBS 2, NBC 5, ABC 7, FOX 32 in Chicago), else the
 * streaming app that has it. Spoiler-free unless show_live_score is set.
 */
final class GameDay {

    /** What the home screen shows; null when there's no game today. */
    static final class Game {
        final String title, detail;
        final Runnable watch;

        Game(String title, String detail, Runnable watch) {
            this.title = title;
            this.detail = detail;
            this.watch = watch;
        }
    }

    interface Listener {
        void onGame(Game game);
    }

    /** A network on the local antenna: ESPN's name for it, the channel number, and how to label it. */
    private static final class Station {
        final String network, number, label;

        Station(String network, String number, String label) {
            this.network = network;
            this.number = number;
            this.label = label;
        }
    }

    /** Chicago over-the-air network affiliates (virtual channel numbers). */
    private static final Station[] STATIONS = {
            new Station("fox", "32.1", "FOX 32"),
            new Station("cbs", "2.1", "CBS 2"),
            new Station("nbc", "5.1", "NBC 5"),
            new Station("abc", "7.1", "ABC 7"),
    };

    /** Streaming-only broadcasts we can open. */
    private static final String[][] STREAMERS = {
            {"prime video", "com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"peacock", "com.peacocktv.peacockandroid", "Peacock"},
            {"netflix", "com.netflix.ninja", "Netflix"},
    };

    private static final String LIVE_TV = "com.vt.tv.tvcenter";
    private static final String SCHEDULE = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams/%s/schedule";
    private static final long REFRESH_GAME_MS = 2 * 60_000L;    // statuses change during a game
    private static final long REFRESH_IDLE_MS = 60 * 60_000L;
    private static final long TICK_MS = 60_000L;
    /** Show from this long before kickoff (if it's the same day) until the game ends. */
    private static final long LEAD_MS = 12 * 3600_000L;
    private static final long MAX_GAME_MS = 5 * 3600_000L;

    private static final class Event {
        long kickoff;
        String opponent, status;
        boolean home;
        int us = -1, them = -1;
        final List<String> networks = new ArrayList<>();
    }

    private final Context ctx;
    private final String team, teamName;
    private final boolean liveScore;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<Event> events = new ArrayList<>();
    private long fetchedAt;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Event e = current();
            boolean during = e != null && System.currentTimeMillis() > e.kickoff - 3600_000L;
            if (System.currentTimeMillis() - fetchedAt > (during ? REFRESH_GAME_MS : REFRESH_IDLE_MS)) fetch();
            else publish();
            main.postDelayed(this, TICK_MS);
        }
    };

    GameDay(Context ctx, String team, String teamName, boolean liveScore, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.team = team;
        this.teamName = teamName;
        this.liveScore = liveScore;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    // ---- schedule --------------------------------------------------------------------------------

    private void fetch() {
        io.execute(() -> {
            List<Event> fresh = load();
            main.post(() -> {
                if (fresh != null) {
                    events = fresh;
                    fetchedAt = System.currentTimeMillis();
                }
                publish();
            });
        });
    }

    private List<Event> load() {
        try {
            JSONObject root = new JSONObject(get(String.format(SCHEDULE, team)));
            JSONArray arr = root.getJSONArray("events");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            List<Event> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ev = arr.getJSONObject(i);
                JSONObject c = ev.getJSONArray("competitions").getJSONObject(0);
                Event e = new Event();
                Date d = iso.parse(ev.getString("date"));
                if (d == null) continue;
                e.kickoff = d.getTime();
                e.status = c.optJSONObject("status") != null
                        ? c.getJSONObject("status").getJSONObject("type").optString("name") : "";
                JSONArray teams = c.getJSONArray("competitors");
                for (int t = 0; t < teams.length(); t++) {
                    JSONObject comp = teams.getJSONObject(t);
                    JSONObject tm = comp.getJSONObject("team");
                    boolean isUs = team.equalsIgnoreCase(tm.optString("abbreviation"));
                    int score = comp.optJSONObject("score") != null
                            ? (int) comp.getJSONObject("score").optDouble("value", -1) : -1;
                    if (isUs) {
                        e.home = "home".equals(comp.optString("homeAway"));
                        e.us = score;
                    } else {
                        e.opponent = tm.optString("shortDisplayName", tm.optString("displayName"));
                        e.them = score;
                    }
                }
                JSONArray bcs = c.optJSONArray("broadcasts");
                if (bcs != null) for (int b = 0; b < bcs.length(); b++) {
                    JSONObject media = bcs.getJSONObject(b).optJSONObject("media");
                    if (media != null) e.networks.add(media.optString("shortName"));
                }
                out.add(e);
            }
            return out;
        } catch (Exception e) {
            android.util.Log.w("GameDay", "schedule failed", e);
            return null;
        }
    }

    /** Today's game, from LEAD_MS before kickoff until it's over (or MAX_GAME_MS after kickoff). */
    private Event current() {
        long now = System.currentTimeMillis();
        for (Event e : events) {
            if ("STATUS_FINAL".equals(e.status) || "STATUS_POSTPONED".equals(e.status)
                    || "STATUS_CANCELED".equals(e.status)) continue;
            boolean sameDay = sameLocalDay(now, e.kickoff);
            if (now >= e.kickoff - LEAD_MS && now <= e.kickoff + MAX_GAME_MS && (sameDay || now >= e.kickoff)) return e;
        }
        return null;
    }

    private void publish() {
        Event e = current();
        if (e == null) {
            listener.onGame(null);
            return;
        }
        Watch w = howToWatch(e);
        String title = teamName + (e.home ? " vs " : " at ") + e.opponent;
        String when;
        if ("STATUS_IN_PROGRESS".equals(e.status) || "STATUS_HALFTIME".equals(e.status)
                || System.currentTimeMillis() >= e.kickoff) {
            when = liveScore && e.us >= 0 && e.them >= 0 ? "Live  " + e.us + "–" + e.them : "Live now";
        } else {
            when = "Today " + DateFormat.getTimeFormat(ctx).format(new Date(e.kickoff));
        }
        listener.onGame(new Game(title, when + "  ·  " + w.label, w.action));
    }

    // ---- watching --------------------------------------------------------------------------------

    private static final class Watch {
        final String label;
        final Runnable action;

        Watch(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    /** Antenna first (no lag, no login), then a streaming app we have; ESPN alone isn't watchable here. */
    private Watch howToWatch(Event e) {
        for (Station s : STATIONS) {
            for (String n : e.networks) {
                if (n.toLowerCase(Locale.US).startsWith(s.network)) return new Watch(s.label, () -> tune(s));
            }
        }
        for (String[] st : STREAMERS) {
            for (String n : e.networks) {
                if (n.toLowerCase(Locale.US).startsWith(st[0])) return new Watch(st[2], () -> openApp(st[1]));
            }
        }
        String name = e.networks.isEmpty() ? "TBD" : e.networks.get(0);
        return new Watch(name, null);
    }

    /**
     * Tune the antenna to {@code s}. Channels come from the TV's own scan. Without that channel (no
     * scan yet), switch Live TV to the antenna input: with no channels it offers "Auto Channel Scan".
     */
    private void tune(Station s) {
        long id = findChannel(s.number);
        if (id >= 0) {
            start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id)));
            return;
        }
        String antenna = antennaInput();
        if (antenna != null) {
            start(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelsUriForInput(antenna), "vnd.android.cursor.dir/channel")
                    .setPackage(LIVE_TV));
            return;
        }
        Intent live = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV);
        if (live != null) start(live);
    }

    private long findChannel(String number) {
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER},
                null, null, null)) {
            if (c == null) return -1;
            String want = number.replace('-', '.');
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n != null && n.replace('-', '.').trim().equals(want)) return c.getLong(0);
            }
        } catch (SecurityException e) {
            android.util.Log.w("GameDay", "no permission to read channels", e);
        }
        return -1;
    }

    /** The TV tuner (antenna/cable) input's id. */
    private String antennaInput() {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER) return info.getId();
        }
        return null;
    }

    private void openApp(String pkg) {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i != null) start(i);
    }

    private void start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.util.Log.w("GameDay", "can't open " + i, e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static boolean sameLocalDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
