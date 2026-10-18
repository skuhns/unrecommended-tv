package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which app sits where: big tiles, the More row, or All apps, each in the viewer's order. Saved on
 * the TV; res/values/config.xml only seeds the first layout. Apps installed later land in All apps.
 */
final class AppLayout {

    enum Section { FRONT, TIER, ALL }

    static final int FRONT_MAX = 6;

    private final SharedPreferences prefs;
    final List<String> front = new ArrayList<>(), tier = new ArrayList<>(), all = new ArrayList<>();

    AppLayout(Context ctx) {
        prefs = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
    }

    /** Line up the saved layout with what's installed now ({@code labels}: package → label). */
    void sync(Context ctx, Map<String, String> labels, Set<String> hidden) {
        front.clear();
        tier.clear();
        all.clear();
        if (prefs.contains("front")) {
            front.addAll(read("front"));
            tier.addAll(read("tier"));
            all.addAll(read("all"));
        } else {
            front.addAll(Arrays.asList(ctx.getResources().getStringArray(R.array.front_apps)));
            tier.addAll(Arrays.asList(ctx.getResources().getStringArray(R.array.tier_apps)));
        }
        for (List<String> l : sections()) l.retainAll(labels.keySet());
        for (List<String> l : sections()) l.removeAll(hidden);

        List<String> fresh = new ArrayList<>();
        for (String pkg : labels.keySet()) {
            if (!hidden.contains(pkg) && !front.contains(pkg) && !tier.contains(pkg) && !all.contains(pkg)) fresh.add(pkg);
        }
        Collections.sort(fresh, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        all.addAll(fresh);
        while (front.size() > FRONT_MAX) tier.add(0, front.remove(front.size() - 1));
        save();
    }

    List<String> list(Section s) {
        return s == Section.FRONT ? front : s == Section.TIER ? tier : all;
    }

    Section sectionOf(String pkg) {
        if (front.contains(pkg)) return Section.FRONT;
        if (tier.contains(pkg)) return Section.TIER;
        return Section.ALL;
    }

    /**
     * Move an app to the end of another section. A full big-tile grid makes room by dropping its
     * last tile to the front of the More row.
     */
    void moveTo(String pkg, Section to) {
        if (sectionOf(pkg) == to) return;
        for (List<String> l : sections()) l.remove(pkg);
        if (to == Section.FRONT && front.size() >= FRONT_MAX) tier.add(0, front.remove(front.size() - 1));
        list(to).add(pkg);
        save();
    }

    void save() {
        prefs.edit()
                .putString("front", String.join("\n", front))
                .putString("tier", String.join("\n", tier))
                .putString("all", String.join("\n", all))
                .apply();
    }

    private List<String> read(String key) {
        String v = prefs.getString(key, "");
        return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split("\n")));
    }

    private Collection<List<String>> sections() {
        return Arrays.asList(front, tier, all);
    }
}
