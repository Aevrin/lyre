/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Long-press / ⋮ menus shared by the library and Now Playing screens. */
final class Actions {
    private Actions() { }

    /** Hooks back into the screen that opened a menu. */
    interface Host {
        Activity activity();
        Remote remote();
        /** Open an album/artist page (null if the screen can't navigate). */
        void openGroup(int kind, String key);
        /** Something changed that the screen should redraw. */
        void refresh();
    }

    // Group kinds, also used as page types in MainActivity.
    static final int ALBUM = 1, ARTIST = 2, GENRE = 3, FOLDER = 4, PLAYLIST = 5, SMART = 6, FAVORITES = 7;

    /** Menu for one song. extra lets the caller add items (e.g. "Remove from playlist"). */
    static void song(Host h, View anchor, Song s, String extraLabel, Runnable extra) {
        Activity a = h.activity();
        PopupMenu m = new PopupMenu(a, anchor);
        Menu mm = m.getMenu();
        mm.add(0, 1, 0, "Play next");
        mm.add(0, 2, 0, "Add to queue");
        mm.add(0, 3, 0, "Add to playlist…");
        boolean fav = LyreApp.get().db().isFavorite(s.path);
        mm.add(0, 11, 0, fav ? "Remove from favorites" : "Add to favorites");
        mm.add(0, 4, 0, "Edit tags");
        mm.add(0, 5, 0, "Go to album");
        mm.add(0, 6, 0, "Go to artist");
        if (extra != null) mm.add(0, 7, 0, extraLabel);
        mm.add(0, 8, 0, "Blacklist song");
        mm.add(0, 9, 0, "Blacklist folder");
        mm.add(0, 10, 0, "Details");
        List<Song> one = Collections.singletonList(s);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: h.remote().playNext(one); Ui.toast(a, "Playing next"); break;
                case 2: h.remote().enqueue(one); Ui.toast(a, "Added to queue"); break;
                case 3: addToPlaylist(h, one); break;
                case 4: editTags(a, one); break;
                case 5: h.openGroup(ALBUM, s.albumKey()); break;
                case 6: h.openGroup(ARTIST, s.albumArtist.toLowerCase(java.util.Locale.ROOT)); break;
                case 7: extra.run(); break;
                case 8: blacklist(h, Db.BL_SONG, s.path, s.title + " — " + s.artist); break;
                case 9: blacklistFolder(h, s.folder); break;
                case 10: details(a, s); break;
                case 11:
                    LyreApp.get().db().setFavorite(s.path, !fav);
                    Ui.toast(a, fav ? "Removed from favorites" : "Added to favorites");
                    h.refresh();
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    /** Menu for an album, artist, genre or folder. */
    static void group(Host h, View anchor, int kind, Library.Group g) {
        Activity a = h.activity();
        PopupMenu m = new PopupMenu(a, anchor);
        Menu mm = m.getMenu();
        mm.add(0, 1, 0, "Play");
        mm.add(0, 2, 0, "Shuffle");
        mm.add(0, 3, 0, "Play next");
        mm.add(0, 4, 0, "Add to queue");
        mm.add(0, 5, 0, "Add to playlist…");
        mm.add(0, 8, 0, "Add all to favorites");
        mm.add(0, 6, 0, "Edit tags (" + Library.plural(g.songs.size(), "song") + ")");
        String what = kind == ALBUM ? "album" : kind == ARTIST ? "artist" : kind == GENRE ? "genre" : "folder";
        mm.add(0, 7, 0, "Blacklist " + what);
        m.setOnMenuItemClickListener(it -> {
            List<Song> songs = g.songs;
            switch (it.getItemId()) {
                case 1: h.remote().play(songs, 0, false); break;
                case 2: h.remote().play(songs, 0, true); break;
                case 3: h.remote().playNext(songs); Ui.toast(a, "Playing next"); break;
                case 4: h.remote().enqueue(songs); Ui.toast(a, "Added to queue"); break;
                case 5: addToPlaylist(h, songs); break;
                case 6: editTags(a, songs); break;
                case 8: favoriteAll(h, songs); break;
                case 7:
                    switch (kind) {
                        case ALBUM: blacklist(h, Db.BL_ALBUM, g.key, g.name + " — " + g.sub); break;
                        case ARTIST: blacklist(h, Db.BL_ARTIST, g.name, g.name); break;
                        case GENRE: blacklist(h, Db.BL_GENRE, g.name, g.name); break;
                        default: blacklistFolder(h, g.key); break;
                    }
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    static void addToPlaylist(Host h, List<Song> songs) {
        Activity a = h.activity();
        Db db = LyreApp.get().db();
        List<Db.Playlist> pls = db.playlists();
        String[] names = new String[pls.size() + 1];
        names[0] = "＋ New playlist…";
        for (int i = 0; i < pls.size(); i++) names[i + 1] = pls.get(i).name;
        List<String> paths = new ArrayList<>(songs.size());
        for (Song s : songs) paths.add(s.path);
        Ui.choose(a, "Add to playlist", names, -1, w -> {
            if (w == 0) {
                Ui.input(a, "New playlist", "", "Create", name -> {
                    long id = db.createPlaylist(name);
                    db.addToPlaylist(id, paths);
                    Ui.toast(a, "Added to " + name);
                    h.refresh();
                });
            } else {
                Db.Playlist p = pls.get(w - 1);
                db.addToPlaylist(p.id, paths);
                Ui.toast(a, "Added to " + p.name);
                h.refresh();
            }
        });
    }

    static void favoriteAll(Host h, List<Song> songs) {
        Db db = LyreApp.get().db();
        for (Song s : songs) db.setFavorite(s.path, true);
        Ui.toast(h.activity(), "Added " + Library.plural(songs.size(), "song") + " to favorites");
        h.refresh();
    }

    static void editTags(Activity a, List<Song> songs) {
        if (songs.isEmpty()) return;
        long[] ids = new long[songs.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = songs.get(i).id;
        a.startActivity(new Intent(a, TagEditorActivity.class).putExtra(TagEditorActivity.EXTRA_IDS, ids));
    }

    static void blacklist(Host h, String type, String value, String label) {
        Prefs p = LyreApp.get().prefs();
        LyreApp.get().db().addBlacklist(type, value, label);
        LyreApp.get().library().refilter();
        Ui.toast(h.activity(), p.blacklistOn() ? "Blacklisted: " + label
                : "Added to the blacklist (the blacklist is switched off)");
    }

    /** Offers the folder and each parent, so a whole tree can be hidden at once. */
    static void blacklistFolder(Host h, String folder) {
        List<String> options = new ArrayList<>();
        String f = folder;
        while (f != null && f.length() > 1 && options.size() < 6) {
            options.add(f);
            int slash = f.lastIndexOf('/');
            f = slash > 0 ? f.substring(0, slash) : null;
            if (f != null && (f.equals("/storage") || f.equals("/storage/emulated"))) break;
        }
        if (options.size() == 1) {
            blacklist(h, Db.BL_FOLDER, folder, folder);
            return;
        }
        String[] items = options.toArray(new String[0]);
        Ui.choose(h.activity(), "Blacklist folder (and subfolders)", items, -1,
                w -> blacklist(h, Db.BL_FOLDER, items[w], items[w]));
    }

    static void details(Activity a, Song s) {
        String msg = "Title: " + s.title
                + "\nArtist: " + s.artist
                + "\nAlbum: " + s.album
                + "\nAlbum artist: " + s.albumArtist
                + "\nGenre: " + s.genre
                + (s.year > 0 ? "\nYear: " + s.year : "")
                + (s.track > 0 ? "\nTrack: " + s.track : "")
                + (s.disc > 0 ? "\nDisc: " + s.disc : "")
                + "\nLength: " + Library.time(s.duration)
                + "\nSize: " + String.format(java.util.Locale.ROOT, "%.1f MB", s.size / 1048576.0)
                + "\nFile: " + s.path;
        Db.Gain g = LyreApp.get().db().gain(s);
        float vol = LyreApp.get().db().volume(s.path);
        if (Math.abs(vol) > 0.01f) msg += String.format(java.util.Locale.ROOT, "\nCustom volume: %+.1f dB", vol);
        Smart.Stat st = LyreApp.get().db().stat(s.path);
        msg += "\nPlays: " + (st == null ? 0 : st.plays) + (st != null && st.skips > 0 ? " (skipped " + st.skips + ")" : "");
        if (st != null && st.lastPlayed > 0) {
            msg += "\nLast played: " + android.text.format.DateUtils.getRelativeTimeSpanString(st.lastPlayed);
        }
        if (g != null) {
            if (!Float.isNaN(g.lufs)) msg += String.format(java.util.Locale.ROOT, "\nLoudness: %.1f LUFS", g.lufs);
            if (!Float.isNaN(g.rgTrack)) msg += String.format(java.util.Locale.ROOT, "\nReplayGain track: %+.2f dB", g.rgTrack);
            if (!Float.isNaN(g.rgAlbum)) msg += String.format(java.util.Locale.ROOT, "\nReplayGain album: %+.2f dB", g.rgAlbum);
        }
        new android.app.AlertDialog.Builder(a).setTitle("Details").setMessage(msg)
                .setPositiveButton("OK", null).show();
    }
}
