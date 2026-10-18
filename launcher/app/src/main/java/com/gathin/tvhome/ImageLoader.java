package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal poster loader: a small thread pool and an in-memory cache. */
final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(String url, ImageView into) {
        into.setTag(url);
        into.setImageDrawable(null);
        if (url == null) return;
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
            if (bmp == null) return;
            CACHE.put(url, bmp);
            Bitmap done = bmp;
            MAIN.post(() -> {
                if (url.equals(into.getTag())) into.setImageBitmap(done);
            });
        });
    }
}
