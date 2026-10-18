package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * The top-right banner. Shows whatever media session is playing on the TV; with nothing playing it
 * shows Spotify and Play resumes Spotify's last track without opening the app.
 */
final class NowPlayingBar {

    static final String SPOTIFY = "com.spotify.tv.android";
    private static final long RESUME_TIMEOUT_MS = 10_000L;
    private static final long REFRESH_MS = 3_000L;

    private final Activity activity;
    private final ComponentName listener;
    private final MediaSessionManager msm;
    private final ImageView art, play;
    private final TextView title, subtitle;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaController controller;

    /** Set when Play was pressed with no Spotify session: start playing once Spotify's session shows up. */
    private long resumeRequestedAt;
    private long lastPlayNudge;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            render();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> refresh();

    /**
     * Safety net while home is visible: Spotify sometimes swaps in a new session (e.g. on skip)
     * without the old one reporting it, which would leave the banner stale.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    NowPlayingBar(Activity activity) {
        this.activity = activity;
        listener = new ComponentName(activity, MediaListener.class);
        msm = (MediaSessionManager) activity.getSystemService(Context.MEDIA_SESSION_SERVICE);
        art = activity.findViewById(R.id.npArt);
        play = activity.findViewById(R.id.npPlay);
        title = activity.findViewById(R.id.npTitle);
        subtitle = activity.findViewById(R.id.npSubtitle);

        activity.findViewById(R.id.npBody).setOnClickListener(v -> openApp());
        play.setOnClickListener(v -> togglePlay());
        activity.findViewById(R.id.npNext).setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
    }

    void start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
        } catch (SecurityException noAccess) {
            // Notification access not granted: stay on the idle Spotify banner.
        }
        refresh();
        handler.postDelayed(tick, REFRESH_MS);
    }

    void stop() {
        handler.removeCallbacks(tick);
        msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        attach(null);
    }

    private void refresh() {
        List<MediaController> sessions;
        try {
            sessions = msm.getActiveSessions(listener);
        } catch (SecurityException noAccess) {
            sessions = null;
        }
        attach(pick(sessions));
        resumeIfWoken();
        render();
    }

    /** Playing session first, then Spotify's, then a paused one that still shows a track; else idle. */
    private static MediaController pick(List<MediaController> sessions) {
        if (sessions == null || sessions.isEmpty()) return null;
        for (MediaController c : sessions) if (isPlaying(c)) return c;
        for (MediaController c : sessions) if (SPOTIFY.equals(c.getPackageName())) return c;
        for (MediaController c : sessions) if (hasTitle(c)) return c;
        return null;
    }

    private static boolean hasTitle(MediaController c) {
        MediaMetadata md = c.getMetadata();
        return md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
    }

    private void attach(MediaController c) {
        if (controller != null && (c == null || !controller.getSessionToken().equals(c.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (c != null && (controller == null || !controller.getSessionToken().equals(c.getSessionToken()))) {
            c.registerCallback(callback, handler);
        }
        controller = c;
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void render() {
        MediaMetadata md = controller != null ? controller.getMetadata() : null;
        String t = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        if (controller == null || t == null) {
            title.setText(R.string.np_idle_title);
            subtitle.setText(R.string.np_idle_subtitle);
            art.setImageDrawable(appIcon(controller != null ? controller.getPackageName() : SPOTIFY));
            play.setImageResource(R.drawable.ic_play);
            return;
        }
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        title.setText(t);
        subtitle.setText(artist != null ? artist : appLabel(controller.getPackageName()));
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp != null) art.setImageBitmap(bmp);
        else art.setImageDrawable(appIcon(controller.getPackageName()));
        play.setImageResource(isPlaying(controller) ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /**
     * After a cold wake Spotify first restores its last track paused, and ignores play() until it has
     * loaded it, so keep asking (rate-limited) until it plays or the attempt times out.
     */
    private void resumeIfWoken() {
        if (resumeRequestedAt == 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - resumeRequestedAt > RESUME_TIMEOUT_MS) {
            resumeRequestedAt = 0;
            return;
        }
        if (controller != null && SPOTIFY.equals(controller.getPackageName())) {
            if (isPlaying(controller)) {
                resumeRequestedAt = 0;
                return;
            }
            if (now - lastPlayNudge > 1_000L && hasTitle(controller)) {
                lastPlayNudge = now;
                controller.getTransportControls().play();
            }
        }
        handler.postDelayed(this::refresh, 500);
    }

    private void togglePlay() {
        if (controller != null) {
            if (isPlaying(controller)) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
            return;
        }
        // No session: wake Spotify in the background with a Play key. That restores its last track
        // (paused); refresh() presses play once its session appears.
        resumeRequestedAt = SystemClock.uptimeMillis();
        AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        handler.postDelayed(() -> {
            if (controller == null) openApp();   // Spotify never woke up: open it instead
        }, RESUME_TIMEOUT_MS);
    }

    private void openApp() {
        String pkg = controller != null ? controller.getPackageName() : SPOTIFY;
        Intent i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private Drawable appIcon(String pkg) {
        try {
            return activity.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return activity.getDrawable(R.drawable.ic_music);
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
