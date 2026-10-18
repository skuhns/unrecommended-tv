package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background art: public-domain paintings from the Art Institute of Chicago (api.artic.edu, no key),
 * a new one every minute. Landscape-shaped paintings only, so they fill a TV. The next image is
 * downloaded while the current one shows.
 */
final class ArtRotator {

    interface Listener {
        void onArt(Bitmap image, String title, String byline);
    }

    private static final long PERIOD_MS = 60_000L;
    /** A piece asked for by id stays up this long before the rotation resumes. */
    private static final long HOLD_MS = 5 * 60_000L;
    private static final String ARTWORK = "https://api.artic.edu/api/v1/artworks/%s?fields=title,artist_title,date_display,image_id";
    private static final String SEARCH = "https://api.artic.edu/api/v1/artworks/search";
    /** IIIF sizes the museum serves: 843 or 1686 px wide. */
    private static final String IMAGE = "https://www.artic.edu/iiif/2/%s/full/1686,/0/default.jpg";
    /** The search only pages through its first 1,000 results (page 11+ is refused): 10 pages of 100. */
    private static final int PAGES = 10;

    private static final class Work {
        final String imageId, title, byline;

        Work(String imageId, String title, String byline) {
            this.imageId = imageId;
            this.title = title;
            this.byline = byline;
        }
    }

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final List<Work> queue = new ArrayList<>();
    private Bitmap nextImage;
    private Work nextWork;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            showNext();
            main.postDelayed(this, PERIOD_MS);
        }
    };

    ArtRotator(Listener listener) {
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

    /** Show one specific artwork now (any in the collection, by id), then resume rotating later. */
    void showNow(String artworkId) {
        io.execute(() -> {
            Work w = null;
            Bitmap img = null;
            try {
                JSONObject a = new JSONObject(new String(get(String.format(ARTWORK, artworkId)), "UTF-8"))
                        .getJSONObject("data");
                w = work(a);
                if (w != null) img = download(String.format(IMAGE, w.imageId));
            } catch (Exception e) {
                android.util.Log.w("ArtRotator", "artwork " + artworkId + " failed", e);
            }
            Bitmap ready = img;
            Work work = w;
            main.post(() -> {
                if (ready == null) return;
                listener.onArt(ready, work.title, work.byline);
                showing = true;
                main.removeCallbacks(tick);
                if (running) main.postDelayed(tick, HOLD_MS);
            });
        });
    }

    /** Show the prefetched painting (if ready) and start fetching the one after. */
    private void showNext() {
        if (nextImage != null) {
            listener.onArt(nextImage, nextWork.title, nextWork.byline);
            nextImage = null;
            showing = true;
        }
        prefetch();
    }

    private boolean showing, fetching;

    private void prefetch() {
        if (fetching || nextImage != null) return;
        fetching = true;
        io.execute(() -> {
            Bitmap img = null;
            Work work = null;
            for (int attempt = 0; attempt < 3 && img == null; attempt++) {
                work = take();
                if (work == null) break;
                img = download(String.format(IMAGE, work.imageId));
            }
            Bitmap ready = img;
            Work w = work;
            main.post(() -> {
                fetching = false;
                if (ready == null) return;
                nextImage = ready;
                nextWork = w;
                // Nothing on screen yet (first run): show it now instead of after a full period.
                if (!showing && running) showNext();
            });
        });
    }

    /** Next painting from a shuffled page of results (runs on the io thread). */
    private Work take() {
        synchronized (queue) {
            if (queue.isEmpty()) queue.addAll(fetchPage(1 + random.nextInt(PAGES)));
            return queue.isEmpty() ? null : queue.remove(0);
        }
    }

    private static List<Work> fetchPage(int page) {
        List<Work> out = new ArrayList<>();
        try {
            String q = SEARCH
                    + "?" + enc("query[bool][must][0][term][is_public_domain]") + "=true"
                    + "&" + enc("query[bool][must][1][term][artwork_type_id]") + "=1"   // paintings
                    + "&fields=title,artist_title,date_display,image_id,thumbnail"
                    + "&limit=100&page=" + page;
            JSONArray data = new JSONObject(new String(get(q), "UTF-8")).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i);
                JSONObject t = a.optJSONObject("thumbnail");
                if (a.isNull("image_id") || t == null) continue;
                double w = t.optDouble("width", 0), h = t.optDouble("height", 1);
                if (w / h < 1.2) continue;   // portrait or square: would be mostly cropped away
                out.add(work(a));
            }
        } catch (Exception e) {
            android.util.Log.w("ArtRotator", "page " + page + " failed", e);
        }
        Collections.shuffle(out);
        return out;
    }

    private static Work work(JSONObject a) {
        if (a.isNull("image_id")) return null;
        String artist = a.optString("artist_title", "");
        String date = a.optString("date_display", "");
        StringBuilder by = new StringBuilder();
        if (!artist.isEmpty() && !"null".equals(artist)) by.append(artist);
        if (!date.isEmpty() && !"null".equals(date)) by.append(by.length() > 0 ? ", " : "").append(date);
        return new Work(a.optString("image_id"), a.optString("title"), by.toString());
    }

    private static Bitmap download(String url) {
        try {
            byte[] bytes = get(url);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; it sits under a scrim
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            android.util.Log.w("ArtRotator", "image failed " + url, e);
            return null;
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        // The museum asks API users to identify themselves.
        c.setRequestProperty("AIC-User-Agent", "tv-home launcher (personal use)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
