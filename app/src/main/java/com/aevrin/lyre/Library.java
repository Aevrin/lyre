/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The music library, straight from Android's media scanner (MediaStore): no
 * scanning of our own. Reloads when MediaStore changes. The blacklist is
 * applied on top and can be switched off without losing its entries.
 */
public final class Library {

    public interface Listener { void onLibraryChanged(); }

    /** An album, artist, genre or folder: a name and its songs. */
    public static final class Group {
        public final String key, name;
        public String sub = "";
        public final List<Song> songs = new ArrayList<>();
        Group(String key, String name) { this.key = key; this.name = name; }
        public Song first() { return songs.isEmpty() ? null : songs.get(0); }
        public long duration() { long d = 0; for (Song s : songs) d += s.duration; return d; }
        public List<String> paths() {
            List<String> p = new ArrayList<>(songs.size());
            for (Song s : songs) p.add(s.path);
            return p;
        }
    }

    public static final int SORT_TITLE = 0, SORT_ARTIST = 1, SORT_ALBUM = 2, SORT_ADDED = 3, SORT_YEAR = 4;

    private final Context ctx;
    private final Db db;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Collator collator = Collator.getInstance();

    // Everything MediaStore returned (before the blacklist), and what's visible.
    private volatile List<Song> all = Collections.emptyList();
    private volatile List<Song> songs = Collections.emptyList();
    private volatile Map<Long, Song> byId = Collections.emptyMap();
    private volatile Map<String, Song> byPath = Collections.emptyMap();
    private volatile List<Group> albums, artists, genres, folders;
    private volatile boolean loaded;
    private volatile int hiddenCount;
    private boolean observing;

    private final Runnable reloadSoon = this::reload;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            // MediaStore fires in bursts while scanning; wait for it to settle.
            main.removeCallbacks(reloadSoon);
            main.postDelayed(reloadSoon, 1500);
        }
    };

    Library(Context c, Db db, Prefs prefs) {
        this.ctx = c.getApplicationContext();
        this.db = db;
        this.prefs = prefs;
        collator.setStrength(Collator.PRIMARY);
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isLoaded() { return loaded; }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Re-reads MediaStore in the background and tells listeners when done. */
    public void reload() {
        if (!hasPermission()) return;
        if (!observing) {
            ctx.getContentResolver().registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer);
            observing = true;
        }
        io.execute(() -> {
            List<Song> list;
            try {
                list = query();
            } catch (RuntimeException e) {
                android.util.Log.w("Lyre", "MediaStore query failed", e);
                return;
            }
            Map<Long, Song> ids = new HashMap<>(list.size() * 2);
            Map<String, Song> paths = new HashMap<>(list.size() * 2);
            for (Song s : list) { ids.put(s.id, s); paths.put(s.path, s); }
            all = list;
            byId = ids;
            byPath = paths;
            applyBlacklistLocked();
            loaded = true;
            main.post(this::notifyListeners);
        });
    }

    /** Re-applies the blacklist (after a change or toggling it) without re-querying. */
    public void refilter() {
        io.execute(() -> {
            applyBlacklistLocked();
            main.post(this::notifyListeners);
        });
    }

    private void notifyListeners() {
        for (Listener l : listeners) l.onLibraryChanged();
    }

    private List<Song> query() {
        ContentResolver cr = ctx.getContentResolver();
        String[] cols = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.COMPOSER,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DISC_NUMBER,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.BITRATE,
        };
        StringBuilder sel = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (prefs.musicOnly()) sel.append(MediaStore.Audio.Media.IS_MUSIC).append(" != 0");
        int min = prefs.minSeconds();
        if (min > 0) {
            if (sel.length() > 0) sel.append(" AND ");
            sel.append(MediaStore.Audio.Media.DURATION).append(" >= ?");
            args.add(String.valueOf(min * 1000L));
        }
        List<Song> out = new ArrayList<>();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                sel.length() == 0 ? null : sel.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]), null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                int rawTrack = c.getInt(9);
                int disc = parseLeadingInt(c.getString(10));
                if (disc <= 0 && rawTrack >= 1000) disc = rawTrack / 1000;
                out.add(new Song(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getString(5), c.getString(6), c.getLong(7), c.getInt(8),
                        rawTrack % 1000, Math.max(disc, 0), c.getLong(11), c.getString(12),
                        c.getLong(13), c.getLong(14), c.getLong(15), c.getInt(16)));
            }
        }
        return out;
    }

    static int parseLeadingInt(String s) {
        if (s == null) return 0;
        int n = 0, i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        boolean any = false;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && n < 100000) {
            n = n * 10 + (s.charAt(i++) - '0');
            any = true;
        }
        return any ? n : 0;
    }

    // ---------------------------------------------------------------- blacklist

    private synchronized void applyBlacklistLocked() {
        List<Song> src = all;
        List<Song> vis;
        if (prefs.blacklistOn()) {
            Matcher m = new Matcher(db.blacklist());
            vis = new ArrayList<>(src.size());
            for (Song s : src) if (!m.matches(s)) vis.add(s);
        } else {
            vis = new ArrayList<>(src);
        }
        sortSongs(vis, SORT_TITLE);
        hiddenCount = src.size() - vis.size();
        songs = vis;
        albums = artists = genres = folders = null;
    }

    /** Tests songs against blacklist entries. */
    static final class Matcher {
        final List<String> folders = new ArrayList<>();
        final java.util.Set<String> songs = new java.util.HashSet<>(), artists = new java.util.HashSet<>(),
                albums = new java.util.HashSet<>(), genres = new java.util.HashSet<>();

        Matcher(List<Db.BlEntry> entries) {
            for (Db.BlEntry e : entries) {
                switch (e.type) {
                    case Db.BL_FOLDER: folders.add(trimSlash(e.value)); break;
                    case Db.BL_SONG: songs.add(e.value); break;
                    case Db.BL_ARTIST: artists.add(e.value.toLowerCase(Locale.ROOT)); break;
                    case Db.BL_ALBUM: albums.add(e.value); break;
                    case Db.BL_GENRE: genres.add(e.value.toLowerCase(Locale.ROOT)); break;
                    default: break;
                }
            }
        }

        boolean matches(Song s) {
            if (songs.contains(s.path)) return true;
            for (String f : folders) {
                if (s.folder.equals(f) || s.folder.startsWith(f + "/")) return true;
            }
            if (!artists.isEmpty() && (artists.contains(s.artist.toLowerCase(Locale.ROOT))
                    || artists.contains(s.albumArtist.toLowerCase(Locale.ROOT)))) return true;
            if (albums.contains(s.albumKey())) return true;
            return !genres.isEmpty() && genres.contains(s.genre.toLowerCase(Locale.ROOT));
        }

        static String trimSlash(String p) {
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        }
    }

    /** How many songs the blacklist is currently hiding. */
    public int hiddenCount() { return hiddenCount; }
    public int totalCount() { return all.size(); }

    // ---------------------------------------------------------------- access

    /** Visible songs (blacklist applied), sorted by title. */
    public List<Song> songs() { return songs; }

    /** Every song MediaStore returned, including blacklisted ones. */
    public List<Song> all() { return all; }

    /** Any song MediaStore knows, blacklisted or not. */
    public Song byId(long id) { return byId.get(id); }
    public Song byPath(String path) { return byPath.get(path); }

    public boolean isVisible(Song s) {
        if (!prefs.blacklistOn()) return true;
        return !new Matcher(db.blacklist()).matches(s);
    }

    /** Resolves stored playlist paths to visible songs, skipping missing files. */
    public List<Song> resolve(List<String> paths) {
        List<Song> out = new ArrayList<>(paths.size());
        Matcher m = prefs.blacklistOn() ? new Matcher(db.blacklist()) : null;
        for (String p : paths) {
            Song s = byPath.get(p);
            if (s != null && (m == null || !m.matches(s))) out.add(s);
        }
        return out;
    }

    public List<Group> albums() {
        List<Group> g = albums;
        if (g == null) {
            g = group(songs, Song::albumKey, s -> s.album, s -> s.albumArtist);
            for (Group x : g) sortAlbumTracks(x.songs);
            final int sort = prefs.albumSort();
            g.sort((a, b) -> {
                if (sort == 1) {
                    int c = collator.compare(a.sub, b.sub);
                    if (c != 0) return c;
                } else if (sort == 2) {
                    int c = Integer.compare(year(b), year(a));
                    if (c != 0) return c;
                }
                return collator.compare(a.name, b.name);
            });
            albums = g;
        }
        return g;
    }

    public List<Group> artists() {
        List<Group> g = artists;
        if (g == null) {
            g = group(songs, s -> s.albumArtist.toLowerCase(Locale.ROOT), s -> s.albumArtist, s -> "");
            for (Group x : g) {
                sortByAlbum(x.songs);
                java.util.Set<String> albumSet = new java.util.HashSet<>();
                for (Song s : x.songs) albumSet.add(s.albumKey());
                x.sub = plural(albumSet.size(), "album") + " · " + plural(x.songs.size(), "song");
            }
            g.sort((a, b) -> collator.compare(a.name, b.name));
            artists = g;
        }
        return g;
    }

    public List<Group> genres() {
        List<Group> g = genres;
        if (g == null) {
            g = group(songs, s -> s.genre.toLowerCase(Locale.ROOT), s -> s.genre, s -> "");
            for (Group x : g) {
                sortByAlbum(x.songs);
                x.sub = plural(x.songs.size(), "song");
            }
            g.sort((a, b) -> collator.compare(a.name, b.name));
            genres = g;
        }
        return g;
    }

    public List<Group> folders() {
        List<Group> g = folders;
        if (g == null) {
            g = group(songs, s -> s.folder, s -> lastSegment(s.folder), s -> "");
            for (Group x : g) {
                x.songs.sort((a, b) -> collator.compare(a.fileName(), b.fileName()));
                x.sub = x.key;
            }
            g.sort((a, b) -> collator.compare(a.key, b.key));
            folders = g;
        }
        return g;
    }

    public Group album(String key) { return find(albums(), key); }
    public Group artist(String key) { return find(artists(), key); }
    public Group genre(String key) { return find(genres(), key); }
    public Group folder(String key) { return find(folders(), key); }

    /** Albums by one artist, for the artist page. */
    public List<Group> albumsBy(String artistKey) {
        List<Group> out = new ArrayList<>();
        for (Group a : albums()) {
            Song f = a.first();
            if (f != null && f.albumArtist.toLowerCase(Locale.ROOT).equals(artistKey)) out.add(a);
        }
        out.sort((a, b) -> Integer.compare(year(a), year(b)));
        return out;
    }

    private static Group find(List<Group> list, String key) {
        for (Group g : list) if (g.key.equals(key)) return g;
        return null;
    }

    private static int year(Group g) {
        Song f = g.first();
        return f == null ? 0 : f.year;
    }

    interface Fn { String of(Song s); }

    private static List<Group> group(List<Song> src, Fn key, Fn name, Fn sub) {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Song s : src) {
            String k = key.of(s);
            Group g = m.get(k);
            if (g == null) {
                g = new Group(k, name.of(s));
                g.sub = sub.of(s);
                m.put(k, g);
            }
            g.songs.add(s);
        }
        return new ArrayList<>(m.values());
    }

    // ---------------------------------------------------------------- sorting

    public void sortSongs(List<Song> list, int sort) {
        Comparator<Song> byTitle = (a, b) -> collator.compare(a.title, b.title);
        switch (sort) {
            case SORT_ARTIST:
                list.sort((a, b) -> {
                    int c = collator.compare(a.artist, b.artist);
                    return c != 0 ? c : byTitle.compare(a, b);
                });
                break;
            case SORT_ALBUM:
                sortByAlbum(list);
                break;
            case SORT_ADDED:
                list.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));
                break;
            case SORT_YEAR:
                list.sort((a, b) -> {
                    int c = Integer.compare(b.year, a.year);
                    return c != 0 ? c : byTitle.compare(a, b);
                });
                break;
            default:
                list.sort(byTitle);
        }
    }

    void sortAlbumTracks(List<Song> list) {
        list.sort((a, b) -> {
            int c = Integer.compare(a.disc, b.disc);
            if (c != 0) return c;
            c = Integer.compare(a.track, b.track);
            return c != 0 ? c : collator.compare(a.title, b.title);
        });
    }

    void sortByAlbum(List<Song> list) {
        list.sort((a, b) -> {
            int c = collator.compare(a.album, b.album);
            if (c != 0) return c;
            c = Integer.compare(a.disc, b.disc);
            if (c != 0) return c;
            c = Integer.compare(a.track, b.track);
            return c != 0 ? c : collator.compare(a.title, b.title);
        });
    }

    // ---------------------------------------------------------------- search

    public List<Song> searchSongs(String q) {
        String n = q.toLowerCase(Locale.ROOT).trim();
        List<Song> out = new ArrayList<>();
        if (n.isEmpty()) return out;
        for (Song s : songs) {
            if (s.title.toLowerCase(Locale.ROOT).contains(n) || s.artist.toLowerCase(Locale.ROOT).contains(n)
                    || s.album.toLowerCase(Locale.ROOT).contains(n)) out.add(s);
        }
        return out;
    }

    public static List<Group> searchGroups(List<Group> groups, String q) {
        String n = q.toLowerCase(Locale.ROOT).trim();
        List<Group> out = new ArrayList<>();
        if (n.isEmpty()) return out;
        for (Group g : groups) if (g.name.toLowerCase(Locale.ROOT).contains(n)) out.add(g);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    public static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
