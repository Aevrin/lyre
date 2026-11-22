/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** One track as Android's media scanner sees it. Immutable. */
public final class Song {
    public final long id;
    public final String title, artist, album, albumArtist, genre, composer;
    public final long albumId;
    public final int year, track, disc;
    public final long duration;   // ms
    public final String path;     // absolute, e.g. /storage/emulated/0/Music/a.flac
    public final String folder;   // parent directory of path
    public final long dateAdded;  // seconds
    public final long dateModified;
    public final long size;
    public final int bitrate;     // bits per second, 0 = unknown

    public Song(long id, String title, String artist, String album, String albumArtist,
                String genre, String composer, long albumId, int year, int track, int disc,
                long duration, String path, long dateAdded, long dateModified, long size) {
        this(id, title, artist, album, albumArtist, genre, composer, albumId, year, track, disc,
                duration, path, dateAdded, dateModified, size, 0);
    }

    public Song(long id, String title, String artist, String album, String albumArtist,
                String genre, String composer, long albumId, int year, int track, int disc,
                long duration, String path, long dateAdded, long dateModified, long size, int bitrate) {
        this.id = id;
        this.path = path == null ? "" : path;
        int slash = this.path.lastIndexOf('/');
        this.folder = slash > 0 ? this.path.substring(0, slash) : "/";
        String file = slash >= 0 ? this.path.substring(slash + 1) : this.path;
        this.title = blank(title) ? stripExt(file) : title;
        this.artist = clean(artist);
        this.album = clean(album);
        this.albumArtist = blank(albumArtist) ? this.artist : albumArtist;
        this.genre = clean(genre);
        this.composer = composer == null ? "" : composer;
        this.albumId = albumId;
        this.year = year;
        this.track = track;
        this.disc = disc;
        this.duration = duration;
        this.dateAdded = dateAdded;
        this.dateModified = dateModified;
        this.size = size;
        this.bitrate = bitrate;
    }

    public Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }

    /** Key that identifies an album regardless of MediaStore's album id. */
    public String albumKey() {
        return album + "\u0001" + albumArtist;
    }

    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public String extension() {
        String f = fileName();
        int dot = f.lastIndexOf('.');
        return dot >= 0 ? f.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty() || "<unknown>".equals(s);
    }

    static String clean(String s) {
        return blank(s) ? "Unknown" : s.trim();
    }

    static String stripExt(String f) {
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }
}
