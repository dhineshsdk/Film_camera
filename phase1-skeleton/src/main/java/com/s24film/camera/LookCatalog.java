package com.s24film.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Metadata for the 120 master looks (assets/looks_index.tsv, generated from Master120LooksDatabase.h). */
final class LookCatalog {
    static final String[] CATEGORY_NAMES = {
        "C-41 Colour Neg", "ECN-2 Motion Picture", "Slide (E-6 / K-14)", "Black & White",
        "Print / Paper", "Digital Cinema", "Fujifilm Sims"
    };
    static final int[] CATEGORY_COLORS = {
        0xFFE59A2B, 0xFF2D6CC0, 0xFF2E8B3D, 0xFF8E8E8E, 0xFFB5651D, 0xFF3A3A3A, 0xFFC2185B
    };

    static final class Look {
        final int index, category, iso;
        final String id, name;
        Look(int index, int category, String id, String name, int iso) {
            this.index = index; this.category = category; this.id = id; this.name = name; this.iso = iso;
        }
        String isoText() { return iso > 0 ? "ISO " + iso : "auto ISO"; }
        String searchKey() {
            return (name + " " + id.replace('_', ' ') + " " + (iso > 0 ? iso : "") + " "
                + CATEGORY_NAMES[category]).toLowerCase(Locale.US);
        }
    }

    private static final String PREFS = "stocks";
    private static final int MAX_RECENT = 12;

    private final SharedPreferences prefs;
    final List<Look> looks = new ArrayList<>();

    LookCatalog(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, 0);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("looks_index.tsv"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 5) continue;
                looks.add(new Look(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2], p[3],
                    Integer.parseInt(p[4])));
            }
        } catch (Exception ignored) {
            // empty catalog: the browser shows nothing, the 12 stock buttons still work
        }
    }

    Look get(int index) {
        return (index >= 0 && index < looks.size()) ? looks.get(index) : null;
    }

    boolean isFavourite(int index) {
        return prefs.getStringSet("fav", new LinkedHashSet<String>()).contains(String.valueOf(index));
    }

    boolean toggleFavourite(int index) {
        Set<String> s = new LinkedHashSet<>(prefs.getStringSet("fav", new LinkedHashSet<String>()));
        String k = String.valueOf(index);
        boolean now = !s.remove(k);
        if (now) s.add(k);
        prefs.edit().putStringSet("fav", s).apply();
        return now;
    }

    List<Integer> recents() {
        List<Integer> out = new ArrayList<>();
        String s = prefs.getString("recent", "");
        if (s.isEmpty()) return out;
        for (String t : s.split(",")) {
            try { out.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    void addRecent(int index) {
        List<Integer> r = recents();
        r.remove(Integer.valueOf(index));
        r.add(0, index);
        while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.size(); i++) { if (i > 0) sb.append(','); sb.append(r.get(i)); }
        prefs.edit().putString("recent", sb.toString()).apply();
    }

    int lastUsed() { return prefs.getInt("last", -1); }
    void setLastUsed(int index) { prefs.edit().putInt("last", index).apply(); }
}
