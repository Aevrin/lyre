/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Everything Lyre keeps for itself in one JSON file: playlists, dynamic
 * playlists, the blacklist, favorites, play counts, loudness measurements and
 * settings. Saved and opened through Android's file picker.
 */
final class Backup {
    private Backup() { }

    /** Settings not worth carrying over (queue position, widget state, one-off flags). */
    private static boolean skipPref(String k) {
        return k.startsWith("q_") || k.startsWith("w_") || k.equals("asked_perm");
    }

    static void write(ContentResolver cr, Uri doc, Db db, Prefs prefs) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "lyre");
        root.put("db_version", db.version());
        root.put("created", System.currentTimeMillis());
        JSONObject tables = new JSONObject();
        for (String t : Db.BACKUP_TABLES) tables.put(t, db.dump(t));
        root.put("tables", tables);
        JSONObject p = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.raw().getAll().entrySet()) {
            if (skipPref(e.getKey()) || e.getValue() == null) continue;
            Object v = e.getValue();
            String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                    : v instanceof Float ? "f" : v instanceof String ? "s" : null;
            if (type == null) continue;
            JSONObject o = new JSONObject();
            o.put("t", type);
            o.put("v", v instanceof Float ? ((Float) v).doubleValue() : v);
            p.put(e.getKey(), o);
        }
        root.put("settings", p);
        try (OutputStream os = cr.openOutputStream(doc, "wt")) {
            if (os == null) throw new IOException("Can't open the file");
            os.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Short description of a backup for the confirmation dialog. */
    static String describe(JSONObject root) {
        JSONObject t = root.optJSONObject("tables");
        if (t == null) return "";
        return count(t, "playlists", "playlist") + ", " + count(t, "smart", "dynamic playlist") + ", "
                + count(t, "blacklist", "blacklist entry").replace("entrys", "entries") + ", "
                + count(t, "favorites", "favorite") + ", play counts for "
                + count(t, "plays", "song") + ", " + count(t, "gains", "measurement");
    }

    private static String count(JSONObject t, String table, String word) {
        JSONArray a = t.optJSONArray(table);
        return Library.plural(a == null ? 0 : a.length(), word);
    }

    static JSONObject read(ContentResolver cr, Uri doc) throws Exception {
        try (InputStream in = cr.openInputStream(doc)) {
            if (in == null) throw new IOException("Can't open the file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            if (!"lyre".equals(root.optString("app"))) throw new IOException("That isn't a Lyre backup");
            return root;
        }
    }

    /** Replaces Lyre's data and settings with the backup's. */
    static void restore(JSONObject root, Db db, Prefs prefs) throws Exception {
        if (root.optInt("db_version", 0) > db.version()) {
            throw new IOException("This backup is from a newer version of Lyre; update Lyre first");
        }
        JSONObject t = root.getJSONObject("tables");
        Map<String, JSONArray> tables = new HashMap<>();
        for (String name : Db.BACKUP_TABLES) {
            JSONArray a = t.optJSONArray(name);
            if (a != null) tables.put(name, a);
        }
        db.load(tables);
        JSONObject p = root.optJSONObject("settings");
        if (p != null) {
            SharedPreferences.Editor ed = prefs.raw().edit();
            Iterator<String> keys = p.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (skipPref(k)) continue;
                JSONObject o = p.getJSONObject(k);
                switch (o.optString("t")) {
                    case "b": ed.putBoolean(k, o.getBoolean("v")); break;
                    case "i": ed.putInt(k, o.getInt("v")); break;
                    case "l": ed.putLong(k, o.getLong("v")); break;
                    case "f": ed.putFloat(k, (float) o.getDouble("v")); break;
                    case "s": ed.putString(k, o.getString("v")); break;
                    default: break;
                }
            }
            ed.commit();
        }
    }
}
