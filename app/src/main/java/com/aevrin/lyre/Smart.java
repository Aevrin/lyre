/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Dynamic ("smart") playlists: a set of rules evaluated against the library
 * every time the playlist is opened, e.g. "added more than 365 days ago",
 * "played at least 5 times", "longer than 10 minutes". Stored as JSON.
 */
public final class Smart {

    // Fields
    public static final int TITLE = 0, ARTIST = 1, ALBUM = 2, ALBUM_ARTIST = 3, GENRE = 4, FOLDER = 5,
            YEAR = 6, LENGTH = 7, PLAYS = 8, LAST_PLAYED = 9, ADDED = 10, SKIPS = 11, FAVORITE = 12;
    public static final String[] FIELD_NAMES = {"Title", "Artist", "Album", "Album artist", "Genre", "Folder",
            "Year", "Length (minutes)", "Play count", "Last played", "Date added", "Skip count", "Favorite"};

    // Operators by field kind
    public static final int CONTAINS = 0, NOT_CONTAINS = 1, IS = 2, IS_NOT = 3, STARTS = 4;   // text
    public static final int GT = 0, LT = 1, EQ = 2, NE = 3, GE = 4, LE = 5;                    // number
    public static final int WITHIN = 0, OLDER = 1, NEVER = 2, EVER = 3;                       // date (days)

    public static final String[] TEXT_OPS = {"contains", "doesn't contain", "is", "isn't", "starts with"};
    public static final String[] NUM_OPS = {"more than", "less than", "is", "isn't", "at least", "at most"};
    public static final String[] DATE_OPS = {"in the last … days", "more than … days ago", "never", "ever"};
    public static final int IS_FAV = 0, NOT_FAV = 1;
    public static final String[] FAV_OPS = {"is a favorite", "isn't a favorite"};

    // Sorts
    public static final int SORT_RANDOM = 0, SORT_TITLE = 1, SORT_ARTIST = 2, SORT_ALBUM = 3, SORT_ADDED_NEW = 4,
            SORT_ADDED_OLD = 5, SORT_MOST_PLAYED = 6, SORT_LEAST_PLAYED = 7, SORT_RECENT = 8, SORT_LONGEST_AGO = 9,
            SORT_LONGEST = 10, SORT_YEAR = 11;
    public static final String[] SORT_NAMES = {"Random", "Title", "Artist", "Album", "Newest added", "Oldest added",
            "Most played", "Least played", "Recently played", "Not played for longest", "Longest", "Year"};

    public static boolean isText(int f) { return f <= FOLDER; }
    public static boolean isDate(int f) { return f == LAST_PLAYED || f == ADDED; }
    public static boolean isFlag(int f) { return f == FAVORITE; }
    public static String[] opsFor(int f) { return isText(f) ? TEXT_OPS : isDate(f) ? DATE_OPS : isFlag(f) ? FAV_OPS : NUM_OPS; }
    /** Date ops "never"/"ever" and yes/no fields take no value. */
    public static boolean needsValue(int f, int op) { return !isFlag(f) && !(isDate(f) && (op == NEVER || op == EVER)); }

    /** Play statistics for one file. */
    public static final class Stat {
        public int plays, skips;
        public boolean favorite;
        public long lastPlayed; // ms, 0 = never
    }

    public static final class Rule {
        public int field, op;
        public String value = "";
        public Rule() { }
        public Rule(int field, int op, String value) { this.field = field; this.op = op; this.value = value; }
    }

    public String name = "";
    public boolean matchAll = true;
    public final List<Rule> rules = new ArrayList<>();
    public int sort = SORT_RANDOM;
    public int limit;          // 0 = no limit (songs)

    // ---------------------------------------------------------------- JSON

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("all", matchAll);
            o.put("sort", sort);
            o.put("limit", limit);
            JSONArray a = new JSONArray();
            for (Rule r : rules) {
                JSONObject j = new JSONObject();
                j.put("f", r.field);
                j.put("o", r.op);
                j.put("v", r.value);
                a.put(j);
            }
            o.put("rules", a);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Smart fromJson(String name, String json) {
        Smart s = new Smart();
        s.name = name;
        try {
            JSONObject o = new JSONObject(json);
            s.matchAll = o.optBoolean("all", true);
            s.sort = o.optInt("sort", SORT_RANDOM);
            s.limit = o.optInt("limit", 0);
            JSONArray a = o.optJSONArray("rules");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                s.rules.add(new Rule(j.optInt("f"), j.optInt("o"), j.optString("v", "")));
            }
        } catch (JSONException ignored) { }
        return s;
    }

    // ---------------------------------------------------------------- presets

    public static String[] PRESET_NAMES = {"Blank", "Recently added", "Most played", "Never played",
            "Not played in a year", "Long tracks (over 10 minutes)", "Favorites I haven't heard lately"};

    public static Smart preset(int i) {
        Smart s = new Smart();
        switch (i) {
            case 1: s.name = "Recently added"; s.rules.add(new Rule(ADDED, WITHIN, "30")); s.sort = SORT_ADDED_NEW; break;
            case 2: s.name = "Most played"; s.rules.add(new Rule(PLAYS, GE, "1")); s.sort = SORT_MOST_PLAYED; s.limit = 100; break;
            case 3: s.name = "Never played"; s.rules.add(new Rule(LAST_PLAYED, NEVER, "")); break;
            case 4: s.name = "Not played in a year"; s.rules.add(new Rule(LAST_PLAYED, OLDER, "365")); s.sort = SORT_LONGEST_AGO; break;
            case 5: s.name = "Long tracks"; s.rules.add(new Rule(LENGTH, GT, "10")); s.sort = SORT_LONGEST; break;
            case 6: s.name = "Forgotten favorites"; s.rules.add(new Rule(FAVORITE, IS_FAV, ""));
                s.rules.add(new Rule(LAST_PLAYED, OLDER, "60")); s.sort = SORT_LONGEST_AGO; break;
            default: s.rules.add(new Rule(ARTIST, CONTAINS, "")); break;
        }
        return s;
    }

    // ---------------------------------------------------------------- evaluation

    /** Songs matching the rules, sorted and limited. now = System.currentTimeMillis(). */
    public List<Song> evaluate(List<Song> songs, Map<String, Stat> stats, long now) {
        List<Song> out = new ArrayList<>();
        for (Song s : songs) if (matches(s, stats.get(s.path), now)) out.add(s);
        sort(out, stats);
        if (limit > 0 && out.size() > limit) out = new ArrayList<>(out.subList(0, limit));
        return out;
    }

    boolean matches(Song s, Stat st, long now) {
        int counted = 0;
        for (Rule r : rules) {
            if (needsValue(r.field, r.op) && r.value.trim().isEmpty()) continue; // unfinished rule: ignore
            counted++;
            boolean m = test(r, s, st, now);
            if (matchAll && !m) return false;
            if (!matchAll && m) return true;
        }
        return matchAll || counted == 0;
    }

    static boolean test(Rule r, Song s, Stat st, long now) {
        if (isFlag(r.field)) {
            boolean fav = st != null && st.favorite;
            return r.op == NOT_FAV ? !fav : fav;
        }
        if (isText(r.field)) {
            String v = text(r.field, s).toLowerCase(Locale.ROOT);
            String q = r.value.trim().toLowerCase(Locale.ROOT);
            switch (r.op) {
                case CONTAINS: return v.contains(q);
                case NOT_CONTAINS: return !v.contains(q);
                case IS: return v.equals(q);
                case IS_NOT: return !v.equals(q);
                case STARTS: return v.startsWith(q);
                default: return false;
            }
        }
        if (isDate(r.field)) {
            long t = r.field == ADDED ? s.dateAdded * 1000L : st == null ? 0 : st.lastPlayed;
            if (r.op == NEVER) return t == 0;
            if (r.op == EVER) return t != 0;
            double days = number(r.value);
            if (Double.isNaN(days)) return false;
            long cutoff = now - (long) (days * 86_400_000L);
            // Never-played counts as "more than N days ago" but not "in the last N days".
            if (r.op == WITHIN) return t != 0 && t >= cutoff;
            if (r.op == OLDER) return t == 0 || t < cutoff;
            return false;
        }
        double q = number(r.value);
        if (Double.isNaN(q)) return false;
        double v;
        switch (r.field) {
            case YEAR: v = s.year; break;
            case LENGTH: v = s.duration / 60000.0; break;
            case PLAYS: v = st == null ? 0 : st.plays; break;
            case SKIPS: v = st == null ? 0 : st.skips; break;
            default: return false;
        }
        switch (r.op) {
            case GT: return v > q;
            case LT: return v < q;
            case EQ: return Math.abs(v - q) < 1e-9;
            case NE: return Math.abs(v - q) >= 1e-9;
            case GE: return v >= q;
            case LE: return v <= q;
            default: return false;
        }
    }

    static String text(int field, Song s) {
        switch (field) {
            case TITLE: return s.title;
            case ARTIST: return s.artist;
            case ALBUM: return s.album;
            case ALBUM_ARTIST: return s.albumArtist;
            case GENRE: return s.genre;
            case FOLDER: return s.folder;
            default: return "";
        }
    }

    static double number(String v) {
        try { return Double.parseDouble(v.trim().replace(',', '.')); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private void sort(List<Song> list, Map<String, Stat> stats) {
        Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        switch (sort) {
            case SORT_TITLE: list.sort((a, b) -> col.compare(a.title, b.title)); break;
            case SORT_ARTIST: list.sort((a, b) -> {
                int c = col.compare(a.artist, b.artist);
                return c != 0 ? c : col.compare(a.title, b.title);
            }); break;
            case SORT_ALBUM: list.sort((a, b) -> {
                int c = col.compare(a.album, b.album);
                if (c != 0) return c;
                c = Integer.compare(a.disc, b.disc);
                return c != 0 ? c : Integer.compare(a.track, b.track);
            }); break;
            case SORT_ADDED_NEW: list.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded)); break;
            case SORT_ADDED_OLD: list.sort((a, b) -> Long.compare(a.dateAdded, b.dateAdded)); break;
            case SORT_MOST_PLAYED: list.sort((a, b) -> Integer.compare(plays(stats, b), plays(stats, a))); break;
            case SORT_LEAST_PLAYED: list.sort((a, b) -> Integer.compare(plays(stats, a), plays(stats, b))); break;
            case SORT_RECENT: list.sort((a, b) -> Long.compare(last(stats, b), last(stats, a))); break;
            case SORT_LONGEST_AGO: list.sort((a, b) -> Long.compare(last(stats, a), last(stats, b))); break;
            case SORT_LONGEST: list.sort((a, b) -> Long.compare(b.duration, a.duration)); break;
            case SORT_YEAR: list.sort((a, b) -> Integer.compare(b.year, a.year)); break;
            default: Collections.shuffle(list, new Random()); break;
        }
    }

    private static int plays(Map<String, Stat> m, Song s) { Stat t = m.get(s.path); return t == null ? 0 : t.plays; }
    private static long last(Map<String, Stat> m, Song s) { Stat t = m.get(s.path); return t == null ? 0 : t.lastPlayed; }

    /** Human-readable summary, e.g. "Length (minutes) more than 10 · Play count at least 5". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) {
            if (needsValue(r.field, r.op) && r.value.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(matchAll ? " · " : " or ");
            String[] ops = opsFor(r.field);
            String op = r.op >= 0 && r.op < ops.length ? ops[r.op] : "?";
            String f = r.field >= 0 && r.field < FIELD_NAMES.length ? FIELD_NAMES[r.field] : "?";
            if (isFlag(r.field)) {
                sb.append(op.substring(0, 1).toUpperCase(Locale.ROOT)).append(op.substring(1));
            } else if (isDate(r.field)) {
                sb.append(f).append(' ').append(needsValue(r.field, r.op) ? op.replace("…", r.value.trim()) : op);
            } else {
                sb.append(f).append(' ').append(op).append(' ').append(r.value.trim());
            }
        }
        return sb.length() == 0 ? "All songs" : sb.toString();
    }
}
