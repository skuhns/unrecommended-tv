package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TMDB search plus "where to watch" (JustWatch data via TMDB), mapped onto the apps on this TV.
 * Blocking calls: run off the main thread.
 */
final class Tmdb {

    static final String IMAGE_BASE = "https://image.tmdb.org/t/p/w185";
    private static final String API = "https://api.themoviedb.org/3";

    static final class Title {
        final String type, name, year, poster, overview;
        final int id;

        Title(String type, int id, String name, String year, String poster, String overview) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.year = year;
            this.poster = poster;
            this.overview = overview;
        }
    }

    /** An installed app that carries a title, and how to open it. */
    static final class Offer {
        final App app;
        final boolean subscription;

        Offer(App app, boolean subscription) {
            this.app = app;
            this.subscription = subscription;
        }
    }

    /**
     * Streaming apps we know how to open. {@code searchUrl} lands on the app's search with the title
     * typed in (verified for YouTube; Apple TV opens its search page empty; Max and Prime Video stop
     * at the profile picker first). Apps without one just open.
     */
    enum App {
        NETFLIX("Netflix", "com.netflix.ninja", null),
        MAX("Max", "com.wbd.stream", "https://play.max.com/search?q=%s"),
        APPLE_TV("Apple TV", "com.apple.atve.androidtv.appletv", "https://tv.apple.com/search?term=%s"),
        PRIME("Prime Video", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com/search?phrase=%s"),
        PEACOCK("Peacock", "com.peacocktv.peacockandroid", null),
        HULU("Hulu", "com.hulu.livingroomplus", null),
        DISNEY("Disney+", "com.disney.disneyplus", null),
        YOUTUBE("YouTube", "com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=%s");

        final String label, pkg, searchUrl;

        App(String label, String pkg, String searchUrl) {
            this.label = label;
            this.pkg = pkg;
            this.searchUrl = searchUrl;
        }

        /** TMDB/JustWatch provider name → app. Add-on channels open in the store that sells them. */
        static App forProvider(String providerName) {
            String n = providerName.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            if (n.contains("amazonchannel") || n.startsWith("amazon") || n.contains("primevideo")) return PRIME;
            if (n.contains("appletvchannel") || n.startsWith("appletv")) return APPLE_TV;
            if (n.contains("rokuchannel") || n.contains("youtubetv")) return null;
            if (n.startsWith("netflix")) return NETFLIX;
            if (n.equals("max") || n.startsWith("hbomax") || n.startsWith("maxamazon")) return MAX;
            if (n.startsWith("peacock")) return PEACOCK;
            if (n.startsWith("hulu")) return HULU;
            if (n.startsWith("disneyplus")) return DISNEY;
            if (n.equals("youtube") || n.equals("youtubepremium") || n.equals("youtubefree")) return YOUTUBE;
            return null;
        }

        Intent intent(Context ctx, String query) {
            if (searchUrl != null && query != null) {
                String url = String.format(searchUrl, Uri.encode(query));
                return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
            }
            Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            return i != null ? i : ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        }

        boolean installed(Context ctx) {
            try {
                ctx.getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
    }

    private final String token, region;

    /** {@code region}: where-to-watch country (ISO code, e.g. US, GB). */
    Tmdb(String token, String region) {
        this.token = token;
        this.region = region;
    }

    boolean configured() {
        return token != null && !token.isEmpty();
    }

    List<Title> search(String query) throws IOException {
        JSONObject res = get("/search/multi?include_adult=false&language=en-US&page=1&query="
                + URLEncoder.encode(query, "UTF-8"));
        List<Title> out = new ArrayList<>();
        JSONArray results = res.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < 15; i++) {
            JSONObject r = results.optJSONObject(i);
            String type = r.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            String name = r.optString(type.equals("movie") ? "title" : "name");
            String date = r.optString(type.equals("movie") ? "release_date" : "first_air_date");
            String poster = r.isNull("poster_path") ? null : IMAGE_BASE + r.optString("poster_path");
            out.add(new Title(type, r.optInt("id"), name, date.length() >= 4 ? date.substring(0, 4) : "",
                    poster, r.optString("overview")));
        }
        return out;
    }

    /** Installed apps that stream the title (subscription/free first), then ones that rent or sell it. */
    List<Offer> offers(Context ctx, Title t) throws IOException {
        JSONObject us = get("/" + t.type + "/" + t.id + "/watch/providers")
                .optJSONObject("results");
        us = us != null ? us.optJSONObject(region) : null;
        Set<App> stream = new LinkedHashSet<>(), buy = new LinkedHashSet<>();
        if (us != null) {
            for (String k : new String[]{"flatrate", "free", "ads"}) collect(us.optJSONArray(k), stream);
            for (String k : new String[]{"rent", "buy"}) collect(us.optJSONArray(k), buy);
        }
        List<Offer> out = new ArrayList<>();
        for (App a : stream) if (a.installed(ctx)) out.add(new Offer(a, true));
        for (App a : buy) if (!stream.contains(a) && a.installed(ctx)) out.add(new Offer(a, false));
        return out;
    }

    private static void collect(JSONArray arr, Set<App> into) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            App a = p != null ? App.forProvider(p.optString("provider_name")) : null;
            if (a != null) into.add(a);
        }
    }

    private JSONObject get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONObject json = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
            if (c.getResponseCode() >= 400) throw new IOException(json.optString("status_message", "HTTP error"));
            return json;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }
}
