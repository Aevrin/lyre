/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lyre's own data: playlists, the blacklist and loudness measurements. Songs
 * themselves always come from MediaStore. Playlists store file paths, not
 * MediaStore ids, because ids change when Android rescans.
 */
public final class Db extends SQLiteOpenHelper {

    public static final String BL_FOLDER = "folder", BL_SONG = "song", BL_ARTIST = "artist",
            BL_ALBUM = "album", BL_GENRE = "genre";

    public static final class Playlist {
        public final long id;
        public final String name;
        public final int count;
        Playlist(long id, String name, int count) { this.id = id; this.name = name; this.count = count; }
    }

    public static final class BlEntry {
        public final long id;
        public final String type, value, label;
        BlEntry(long id, String type, String value, String label) {
            this.id = id; this.type = type; this.value = value; this.label = label;
        }
    }

    /** Cached loudness info for one file. NaN = unknown. */
    public static final class Gain {
        public float lufs = Float.NaN, peak = Float.NaN, rgTrack = Float.NaN, rgAlbum = Float.NaN;
        public boolean rgChecked;
    }

    public Db(Context c) {
        super(c, "lyre.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER)");
        db.execSQL("CREATE TABLE playlist_items (playlist_id INTEGER NOT NULL, pos INTEGER NOT NULL, path TEXT NOT NULL)");
        db.execSQL("CREATE INDEX pi_pl ON playlist_items(playlist_id, pos)");
        db.execSQL("CREATE TABLE blacklist (_id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, value TEXT NOT NULL, label TEXT, UNIQUE(type, value))");
        db.execSQL("CREATE TABLE gains (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, lufs REAL, peak REAL, rg_track REAL, rg_album REAL, rg_checked INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    // ---------------------------------------------------------------- playlists

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT p._id, p.name, (SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id = p._id) " +
                "FROM playlists p ORDER BY p.name COLLATE NOCASE", null)) {
            while (c.moveToNext()) out.add(new Playlist(c.getLong(0), c.getString(1), c.getInt(2)));
        }
        return out;
    }

    public long createPlaylist(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert("playlists", null, v);
    }

    public void renamePlaylist(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("playlists", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void deletePlaylist(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] a = {String.valueOf(id)};
        db.delete("playlist_items", "playlist_id=?", a);
        db.delete("playlists", "_id=?", a);
    }

    public String playlistName(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name FROM playlists WHERE _id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public List<String> playlistPaths(long id) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT path FROM playlist_items WHERE playlist_id=? ORDER BY pos",
                new String[]{String.valueOf(id)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public void addToPlaylist(long id, List<String> paths) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int pos = 0;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(pos) + 1, 0) FROM playlist_items WHERE playlist_id=?",
                    new String[]{String.valueOf(id)})) {
                if (c.moveToFirst()) pos = c.getInt(0);
            }
            for (String p : paths) {
                ContentValues v = new ContentValues();
                v.put("playlist_id", id);
                v.put("pos", pos++);
                v.put("path", p);
                db.insert("playlist_items", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Replaces a playlist's contents (used for reorder and remove). */
    public void setPlaylist(long id, List<String> paths) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("playlist_items", "playlist_id=?", new String[]{String.valueOf(id)});
            int pos = 0;
            for (String p : paths) {
                ContentValues v = new ContentValues();
                v.put("playlist_id", id);
                v.put("pos", pos++);
                v.put("path", p);
                db.insert("playlist_items", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Keeps playlists pointing at a file after it has been moved or rewritten elsewhere. */
    public void replacePath(String oldPath, String newPath) {
        ContentValues v = new ContentValues();
        v.put("path", newPath);
        getWritableDatabase().update("playlist_items", v, "path=?", new String[]{oldPath});
    }

    // ---------------------------------------------------------------- blacklist

    public List<BlEntry> blacklist() {
        List<BlEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, type, value, label FROM blacklist ORDER BY type, label COLLATE NOCASE", null)) {
            while (c.moveToNext()) out.add(new BlEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)));
        }
        return out;
    }

    public void addBlacklist(String type, String value, String label) {
        ContentValues v = new ContentValues();
        v.put("type", type);
        v.put("value", value);
        v.put("label", label);
        getWritableDatabase().insertWithOnConflict("blacklist", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removeBlacklist(long id) {
        getWritableDatabase().delete("blacklist", "_id=?", new String[]{String.valueOf(id)});
    }

    public void clearBlacklist() {
        getWritableDatabase().delete("blacklist", null, null);
    }

    // ---------------------------------------------------------------- loudness

    /** The cached entry, or null if there is none or the file has changed since. */
    public Gain gain(Song s) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT size, mtime, lufs, peak, rg_track, rg_album, rg_checked FROM gains WHERE path=?",
                new String[]{s.path})) {
            if (!c.moveToFirst()) return null;
            if (c.getLong(0) != s.size || c.getLong(1) != s.dateModified) return null;
            Gain g = new Gain();
            g.lufs = c.isNull(2) ? Float.NaN : c.getFloat(2);
            g.peak = c.isNull(3) ? Float.NaN : c.getFloat(3);
            g.rgTrack = c.isNull(4) ? Float.NaN : c.getFloat(4);
            g.rgAlbum = c.isNull(5) ? Float.NaN : c.getFloat(5);
            g.rgChecked = c.getInt(6) != 0;
            return g;
        }
    }

    public void putGain(Song s, Gain g) {
        ContentValues v = new ContentValues();
        v.put("path", s.path);
        v.put("size", s.size);
        v.put("mtime", s.dateModified);
        put(v, "lufs", g.lufs);
        put(v, "peak", g.peak);
        put(v, "rg_track", g.rgTrack);
        put(v, "rg_album", g.rgAlbum);
        v.put("rg_checked", g.rgChecked ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("gains", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int measuredCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM gains WHERE lufs IS NOT NULL", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clearGains() {
        getWritableDatabase().delete("gains", null, null);
    }

    private static void put(ContentValues v, String k, float f) {
        if (Float.isNaN(f)) v.putNull(k); else v.put(k, f);
    }
}
