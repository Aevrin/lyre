/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagTextField;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tag reading and writing with jaudiotagger. Reads go straight to the file
 * (allowed by the audio permission). Writes work on a copy in the app's cache,
 * then the copy is written back through MediaStore, which is the only way to
 * modify another app's file on Android 11+ without "all files access".
 */
public final class Tags {
    private Tags() { }

    /** The fields the editor shows, in order, with their labels. */
    public static final FieldKey[] KEYS = {
            FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.ALBUM_ARTIST, FieldKey.GENRE,
            FieldKey.YEAR, FieldKey.TRACK, FieldKey.DISC_NO, FieldKey.COMPOSER, FieldKey.COMMENT,
    };
    public static final String[] LABELS = {
            "Title", "Artist", "Album", "Album artist", "Genre",
            "Year", "Track", "Disc", "Composer", "Comment",
    };

    /** Current tag values of a file; empty strings for missing fields. */
    public static Map<FieldKey, String> read(Song s) throws Exception {
        AudioFile af = AudioFileIO.read(new File(s.path));
        Tag tag = af.getTag();
        Map<FieldKey, String> out = new LinkedHashMap<>();
        for (FieldKey k : KEYS) {
            String v = "";
            if (tag != null) {
                try { v = tag.getFirst(k); } catch (RuntimeException ignored) { }
            }
            out.put(k, v == null ? "" : v);
        }
        return out;
    }

    /** Embedded cover art bytes, or null. */
    public static byte[] readArt(Song s) {
        try {
            Tag tag = AudioFileIO.read(new File(s.path)).getTag();
            Artwork a = tag == null ? null : tag.getFirstArtwork();
            return a == null ? null : a.getBinaryData();
        } catch (Exception | Error e) {
            return null;
        }
    }

    /** ReplayGain {track, album} in dB from the file's tags; NaN where absent. */
    public static float[] replayGain(Song s) {
        float[] out = {Float.NaN, Float.NaN};
        try {
            Tag tag = AudioFileIO.read(new File(s.path)).getTag();
            if (tag == null) return out;
            if (tag instanceof AbstractID3v2Tag) {
                for (TagField f : tag.getFields("TXXX")) {
                    if (!(f instanceof AbstractID3v2Frame)) continue;
                    Object body = ((AbstractID3v2Frame) f).getBody();
                    if (!(body instanceof FrameBodyTXXX)) continue;
                    FrameBodyTXXX t = (FrameBodyTXXX) body;
                    take(out, t.getDescription(), t.getFirstTextValue());
                }
            }
            Iterator<TagField> it = tag.getFields();
            while (it.hasNext()) {
                TagField f = it.next();
                if (f instanceof TagTextField) take(out, f.getId(), ((TagTextField) f).getContent());
            }
        } catch (Exception | Error e) {
            android.util.Log.d("Lyre", "ReplayGain: can't read " + s.path + ": " + e);
        }
        return out;
    }

    private static void take(float[] out, String id, String value) {
        if (id == null || value == null) return;
        String k = id.toLowerCase(Locale.ROOT);
        if (k.endsWith("replaygain_track_gain")) out[0] = parseDb(value, out[0]);
        else if (k.endsWith("replaygain_album_gain")) out[1] = parseDb(value, out[1]);
    }

    /** "-6.48 dB" → -6.48. Keeps the old value if it can't be parsed. */
    static float parseDb(String v, float fallback) {
        String t = v.trim().toLowerCase(Locale.ROOT).replace("db", "").replace(',', '.').trim();
        try {
            float f = Float.parseFloat(t);
            return Float.isNaN(f) || Math.abs(f) > 60 ? fallback : f;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** What to do with cover art in an edit. */
    public static final class ArtChange {
        public final boolean remove;
        public final byte[] data;
        public final String mime;
        private ArtChange(boolean remove, byte[] data, String mime) { this.remove = remove; this.data = data; this.mime = mime; }
        public static ArtChange remove() { return new ArtChange(true, null, null); }
        public static ArtChange set(byte[] data, String mime) { return new ArtChange(false, data, mime); }
    }

    /**
     * Applies changes to one file. Empty values delete the field. The caller
     * must already hold write access to the song's MediaStore URI.
     */
    public static void write(Context ctx, Song s, Map<FieldKey, String> changes, ArtChange art) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        File dir = new File(ctx.getCacheDir(), "tagedit");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String ext = s.extension().isEmpty() ? "mp3" : s.extension();
        File tmp = new File(dir, "edit-" + s.id + "." + ext);
        try {
            try (InputStream in = cr.openInputStream(s.uri()); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't open " + s.path);
                copy(in, out);
            }
            AudioFile af = AudioFileIO.read(tmp);
            Tag tag = af.getTagOrCreateAndSetDefault();
            for (Map.Entry<FieldKey, String> e : changes.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) {
                    try { tag.deleteField(e.getKey()); } catch (RuntimeException ignored) { }
                } else {
                    tag.setField(e.getKey(), v);
                }
            }
            if (art != null) {
                tag.deleteArtworkField();
                if (!art.remove && art.data != null) {
                    Artwork a = ArtworkFactory.getNew();
                    a.setBinaryData(art.data);
                    a.setMimeType(art.mime == null ? "image/jpeg" : art.mime);
                    a.setPictureType(PictureTypes.DEFAULT_ID);
                    a.setDescription("");
                    tag.setField(a);
                }
            }
            af.setTag(tag);
            AudioFileIO.write(af);
            try (InputStream in = new FileInputStream(tmp); OutputStream out = cr.openOutputStream(s.uri(), "wt")) {
                if (out == null) throw new IOException("Can't write " + s.path);
                copy(in, out);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Asks the media scanner to re-read files after their tags change. */
    public static void rescan(Context ctx, List<String> paths, Runnable done) {
        if (paths.isEmpty()) { if (done != null) done.run(); return; }
        final int[] left = {paths.size()};
        MediaScannerConnection.scanFile(ctx.getApplicationContext(), paths.toArray(new String[0]), null,
                (path, uri) -> {
                    synchronized (left) {
                        if (--left[0] == 0 && done != null) done.run();
                    }
                });
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
