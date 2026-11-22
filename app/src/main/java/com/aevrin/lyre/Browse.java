/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The library as a browse tree for car stereos, Bluetooth head units, Android
 * Auto and other media browsers.
 *
 * Ids: "root", "artists", "albums", "genres", "folders", "playlists", "songs";
 * "artist:KEY", "album:KEY", "genre:KEY", "folder:KEY", "playlist:ID", "smart:ID", "fav";
 * and playable songs "s:SONGID@CONTEXT", where CONTEXT is the list it was picked
 * from, so choosing a song plays the whole album/playlist starting there.
 */
final class Browse {
    private Browse() { }

    static final String ROOT = "root";

    static MediaItem root() {
        return folder(ROOT, "Lyre", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
    }

    static List<MediaItem> children(String parentId) {
        Library lib = LyreApp.get().library();
        Db db = LyreApp.get().db();
        List<MediaItem> out = new ArrayList<>();
        switch (parentId) {
            case ROOT:
                out.add(folder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS));
                out.add(folder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS));
                out.add(folder("genres", "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES));
                out.add(folder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS));
                out.add(folder("folders", "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED));
                out.add(folder("songs", "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED));
                return out;
            case "artists":
                for (Library.Group g : lib.artists()) out.add(group("artist:" + g.key, g.name, g.sub, g.first(), MediaMetadata.MEDIA_TYPE_ARTIST));
                return out;
            case "albums":
                for (Library.Group g : lib.albums()) out.add(group("album:" + g.key, g.name, g.sub, g.first(), MediaMetadata.MEDIA_TYPE_ALBUM));
                return out;
            case "genres":
                for (Library.Group g : lib.genres()) out.add(group("genre:" + g.key, g.name, g.sub, g.first(), MediaMetadata.MEDIA_TYPE_GENRE));
                return out;
            case "folders":
                for (Library.Group g : lib.folders()) out.add(group("folder:" + g.key, g.name, g.key, g.first(), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED));
                return out;
            case "playlists":
                out.add(group("fav", "Favorites", "", null, MediaMetadata.MEDIA_TYPE_PLAYLIST));
                for (Db.SmartRow r : db.smartPlaylists()) out.add(group("smart:" + r.id, r.smart.name, r.smart.describe(), null, MediaMetadata.MEDIA_TYPE_PLAYLIST));
                for (Db.Playlist p : db.playlists()) out.add(group("playlist:" + p.id, p.name, Library.plural(p.count, "song"), null, MediaMetadata.MEDIA_TYPE_PLAYLIST));
                return out;
            default:
                break;
        }
        if (parentId.startsWith("artist:")) {
            // An artist: their albums, then every song.
            String key = parentId.substring(7);
            for (Library.Group a : lib.albumsBy(key)) out.add(group("album:" + a.key, a.name, a.sub, a.first(), MediaMetadata.MEDIA_TYPE_ALBUM));
        }
        List<Song> songs = songsFor(parentId);
        for (Song s : songs) out.add(song(s, parentId));
        return out;
    }

    /** The songs behind a list id, in the order the screen shows them. */
    static List<Song> songsFor(String ctx) {
        Library lib = LyreApp.get().library();
        Db db = LyreApp.get().db();
        int colon = ctx.indexOf(':');
        String kind = colon < 0 ? ctx : ctx.substring(0, colon);
        String key = colon < 0 ? "" : ctx.substring(colon + 1);
        Library.Group g = null;
        switch (kind) {
            case "songs": return lib.songs();
            case "fav": return lib.resolve(db.favorites());
            case "artist": g = lib.artist(key); break;
            case "album": g = lib.album(key); break;
            case "genre": g = lib.genre(key); break;
            case "folder": g = lib.folder(key); break;
            case "playlist":
                try { return lib.resolve(db.playlistPaths(Long.parseLong(key))); }
                catch (NumberFormatException e) { return Collections.emptyList(); }
            case "smart":
                try {
                    Smart sm = db.smart(Long.parseLong(key));
                    return sm == null ? Collections.<Song>emptyList()
                            : sm.evaluate(lib.songs(), db.stats(), System.currentTimeMillis());
                } catch (NumberFormatException e) { return Collections.emptyList(); }
            default: return Collections.emptyList();
        }
        return g == null ? Collections.<Song>emptyList() : g.songs;
    }

    /** "s:123@album:x" → 123, or -1. */
    static long songId(String mediaId) {
        if (!mediaId.startsWith("s:")) return -1;
        int at = mediaId.indexOf('@');
        try { return Long.parseLong(at < 0 ? mediaId.substring(2) : mediaId.substring(2, at)); }
        catch (NumberFormatException e) { return -1; }
    }

    /** "s:123@album:x" → "album:x", or null. */
    static String context(String mediaId) {
        int at = mediaId.indexOf('@');
        return mediaId.startsWith("s:") && at > 0 ? mediaId.substring(at + 1) : null;
    }

    static MediaItem item(String mediaId) {
        long id = songId(mediaId);
        if (id >= 0) {
            Song s = LyreApp.get().library().byId(id);
            return s == null ? null : song(s, context(mediaId));
        }
        return folder(mediaId, mediaId, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
    }

    private static MediaItem song(Song s, String ctx) {
        MediaItem base = PlaybackService.item(s);
        return base.buildUpon().setMediaId("s:" + s.id + (ctx == null ? "" : "@" + ctx)).build();
    }

    private static MediaItem folder(String id, String title, int type) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(type)
                        .build())
                .build();
    }

    /** A browsable list that can also be played as a whole. */
    private static MediaItem group(String id, String title, String sub, Song art, int type) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(sub)
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(type);
        if (art != null) md.setArtworkUri(Uri.parse("lyreart://" + art.id));
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build();
    }
}
