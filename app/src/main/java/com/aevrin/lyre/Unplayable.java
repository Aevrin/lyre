/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.Context;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.util.Set;

/** Why a file can't be played, or null if it looks fine. */
public final class Unplayable {
    private Unplayable() { }

    /** Formats neither Media3 nor stock Android can decode. */
    static final Set<String> UNSUPPORTED = Set.of(
            "wma", "asf", "ape", "wv", "dsf", "dff", "mpc", "ra", "rm", "tak", "ofr", "tta");

    /** Instant checks: known-unsupported formats, empty files, and files that already failed in Lyre. */
    public static String quick(Song s, java.util.Map<String, String> failures) {
        String ext = s.extension();
        if (UNSUPPORTED.contains(ext)) return ext.toUpperCase(java.util.Locale.ROOT) + " isn't supported on Android";
        if (s.size == 0) return "Empty file";
        String f = failures.get(s.path);
        if (f != null) return "Failed to play in Lyre (" + f + ")";
        return null;
    }

    /** Opens the file and checks that Android has a decoder for its audio. Slow; off the main thread. */
    public static String deep(Context ctx, Song s) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, s.uri(), null);
            MediaFormat audio = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { audio = f; break; }
            }
            if (audio == null) return "No audio found in the file";
            String mime = audio.getString(MediaFormat.KEY_MIME);
            // Media3 decodes FLAC, Opus, Vorbis and raw PCM itself where the platform can't.
            if ("audio/raw".equals(mime) || "audio/flac".equals(mime) || "audio/opus".equals(mime)
                    || "audio/vorbis".equals(mime)) return null;
            if (new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(audio) == null) {
                return "No decoder for " + mime;
            }
            return null;
        } catch (Exception e) {
            return "Can't read the file (damaged or unsupported)";
        } finally {
            ex.release();
        }
    }
}
