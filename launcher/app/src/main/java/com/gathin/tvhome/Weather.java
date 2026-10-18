package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Current temperature and an emoji for the sky, from Open-Meteo (free, no key), every 15 minutes. */
final class Weather {

    interface Listener {
        /** e.g. "61° ☀️"; null if it couldn't be fetched. */
        void onWeather(String text);
    }

    private static final String URL_FMT = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
            + "&current=temperature_2m,weather_code,is_day&temperature_unit=%s";
    private static final long REFRESH_MS = 15 * 60_000L;

    private final String lat, lon, unit;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long fetchedAt;
    private String last;

    Weather(String lat, String lon, boolean fahrenheit, Listener listener) {
        this.lat = lat;
        this.lon = lon;
        this.unit = fahrenheit ? "fahrenheit" : "celsius";
        this.listener = listener;
    }

    /** Show the last reading right away; fetch a new one if it's older than 15 minutes. */
    void refresh() {
        if (last != null) listener.onWeather(last);
        if (System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        fetchedAt = System.currentTimeMillis();
        io.execute(() -> {
            String text = null;
            try {
                JSONObject cur = new JSONObject(get(String.format(Locale.US, URL_FMT, lat, lon, unit)))
                        .getJSONObject("current");
                long temp = Math.round(cur.getDouble("temperature_2m"));
                text = temp + "°  " + emoji(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            } catch (Exception e) {
                fetchedAt = 0;   // try again next time
            }
            String done = text;
            main.post(() -> {
                if (done != null) last = done;
                listener.onWeather(last);
            });
        });
    }

    /** WMO weather interpretation codes (open-meteo.com/en/docs). */
    private static String emoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1 || code == 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "🌧️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
