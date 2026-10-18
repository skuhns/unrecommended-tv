package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The antenna channels from the TV's own scan (TvContract), and their program guide. Only the
 * channels listed in config.xml (live_channels, "number|label") are used; the TV's Live TV app keeps
 * the full list. Needs READ_TV_LISTINGS (granted by build-and-install.sh).
 */
final class TvChannels {

    static final String LIVE_TV = "com.vt.tv.tvcenter";
    /** Time for the channel to start playing before the picture menu is opened over it. */
    private static final long LOOK_DELAY_MS = 6_000L;

    /** One of the configured channels: its number, label, and the scanned row to tune. */
    static final class Channel {
        final String number, label;
        final long id;

        Channel(String number, String label, long id) {
            this.number = number;
            this.label = label;
            this.id = id;
        }
    }

    /** A guide entry on one of the configured channels. */
    static final class Program {
        final Channel channel;
        final String title;
        final long start, end;

        Program(Channel channel, String title, long start, long end) {
            this.channel = channel;
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private final Context ctx;
    private final String[] configured;

    TvChannels(Context ctx, String[] liveChannels) {
        this.ctx = ctx.getApplicationContext();
        this.configured = liveChannels;
    }

    /** The configured channels that the scan found, in config order. */
    List<Channel> channels() {
        List<Channel> out = new ArrayList<>();
        for (String line : configured) {
            String[] p = line.split("\\|");
            if (p.length < 2) continue;
            long id = find(p[0].trim());
            if (id >= 0) out.add(new Channel(p[0].trim(), p[1].trim(), id));
        }
        return out;
    }

    /**
     * The scanned channel with this number. Stations often broadcast twice: classic (ATSC 1.0) and
     * NextGen TV (ATSC 3.0, names ending "-NG"). Prefer NextGen: it carries the program guide, and in
     * testing it came in where classic VHF stations (CBS 2, FOX 32 in Chicago) were too weak indoors.
     */
    long find(String number) {
        long classic = -1;
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                        TvContract.Channels.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return -1;
            String want = number.replace('-', '.');
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n == null || !n.replace('-', '.').trim().equals(want)) continue;
                String name = c.getString(2);
                if (name != null && name.trim().toUpperCase(Locale.US).endsWith("-NG")) return c.getLong(0);
                if (classic < 0) classic = c.getLong(0);
            }
        } catch (SecurityException e) {
            android.util.Log.w("TvChannels", "no permission to read channels", e);
        }
        return classic;
    }

    /** What's on each channel right now (null title when the station sends no guide). */
    List<Program> onNow() {
        long now = System.currentTimeMillis();
        List<Program> out = new ArrayList<>();
        for (Channel ch : channels()) {
            Program p = null;
            for (Program g : guide(ch, now, now)) {
                p = g;
                break;
            }
            out.add(p != null ? p : new Program(ch, null, 0, 0));
        }
        return out;
    }

    /** Guide entries on the configured channels whose title contains {@code query}, from now on. */
    List<Program> search(String query) {
        long now = System.currentTimeMillis();
        String q = query.toLowerCase(Locale.US);
        List<Program> out = new ArrayList<>();
        for (Channel ch : channels()) {
            for (Program p : guide(ch, now, Long.MAX_VALUE)) {
                if (p.title != null && p.title.toLowerCase(Locale.US).contains(q)) out.add(p);
            }
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** Programs on {@code ch} that end after {@code from} and start before {@code to}. */
    private List<Program> guide(Channel ch, long from, long to) {
        List<Program> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.buildProgramsUriForChannel(ch.id),
                new String[]{TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                        TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long start = c.getLong(1), end = c.getLong(2);
                if (end > from && start <= to) out.add(new Program(ch, c.getString(0), start, end));
            }
        } catch (Exception e) {
            android.util.Log.w("TvChannels", "guide failed for " + ch.number, e);
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** Tune Live TV to {@code id}, then switch to the live_tv_look picture look once it's playing. */
    void tune(long id) {
        start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id)));
        PicturePanel.applySoon(ctx, ctx.getString(R.string.live_tv_look), LOOK_DELAY_MS);
    }

    /** Live TV on the antenna input: with no channels scanned it offers "Auto Channel Scan". */
    void openAntenna() {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_TUNER) {
                start(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelsUriForInput(info.getId()), "vnd.android.cursor.dir/channel")
                        .setPackage(LIVE_TV));
                return;
            }
        }
        Intent live = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV);
        if (live != null) start(live);
    }

    private void start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.util.Log.w("TvChannels", "can't open " + i, e);
        }
    }
}
