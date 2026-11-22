/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds duplicate songs, two ways:
 * <ul>
 *   <li><b>Same song</b>: same title and artist (ignoring case, punctuation and
 *       accents) and lengths within a few seconds, even if the files differ
 *       (an MP3 and a FLAC of the same track, say).</li>
 *   <li><b>Identical files</b>: byte-for-byte the same content (SHA-256).</li>
 * </ul>
 * In each group one copy is suggested to keep: lossless first, then the highest
 * bitrate, then the biggest file.
 */
public final class Dupes {
    private Dupes() { }

    public static final long TOLERANCE_MS = 3000;
    private static final Set<String> LOSSLESS = Set.of("flac", "wav", "aiff", "aif", "alac", "ape", "wv", "dsf", "dff");

    /** Groups of 2+ songs that look like the same recording. */
    public static List<List<Song>> similar(List<Song> songs) {
        Map<String, List<Song>> byName = new LinkedHashMap<>();
        for (Song s : songs) {
            String k = norm(s.title) + "\u0001" + norm(s.artist);
            if (k.length() <= 1) continue;
            byName.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
        }
        List<List<Song>> out = new ArrayList<>();
        for (List<Song> same : byName.values()) {
            if (same.size() < 2) continue;
            // Within one title/artist, cluster by length (live vs studio versions differ a lot).
            same.sort((a, b) -> Long.compare(a.duration, b.duration));
            List<Song> cur = new ArrayList<>();
            for (Song s : same) {
                if (!cur.isEmpty() && s.duration - cur.get(cur.size() - 1).duration > TOLERANCE_MS) {
                    if (cur.size() > 1) out.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(s);
            }
            if (cur.size() > 1) out.add(cur);
        }
        for (List<Song> g : out) sortBestFirst(g);
        return out;
    }

    /** Reads a file's bytes for hashing. */
    public interface Opener { InputStream open(Song s) throws IOException; }

    public interface Progress { void at(int done, int total); boolean cancelled(); }

    /** Groups of byte-identical files. Only files sharing a size are hashed. */
    public static List<List<Song>> identical(List<Song> songs, Opener opener, Progress progress) {
        Map<Long, List<Song>> bySize = new HashMap<>();
        for (Song s : songs) if (s.size > 0) bySize.computeIfAbsent(s.size, x -> new ArrayList<>()).add(s);
        List<Song> candidates = new ArrayList<>();
        for (List<Song> l : bySize.values()) if (l.size() > 1) candidates.addAll(l);
        Map<String, List<Song>> byHash = new LinkedHashMap<>();
        int done = 0;
        for (Song s : candidates) {
            if (progress != null) {
                if (progress.cancelled()) break;
                progress.at(done, candidates.size());
            }
            String h = hash(opener, s);
            if (h != null) byHash.computeIfAbsent(s.size + ":" + h, x -> new ArrayList<>()).add(s);
            done++;
        }
        List<List<Song>> out = new ArrayList<>();
        for (List<Song> g : byHash.values()) if (g.size() > 1) { sortBestFirst(g); out.add(g); }
        return out;
    }

    private static String hash(Opener opener, Song s) {
        try (InputStream in = opener.open(s)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Best copy first: lossless, then bitrate, then size, then shortest path. */
    public static void sortBestFirst(List<Song> g) {
        g.sort((a, b) -> {
            int c = Boolean.compare(lossless(b), lossless(a));
            if (c != 0) return c;
            c = Integer.compare(b.bitrate, a.bitrate);
            if (c != 0) return c;
            c = Long.compare(b.size, a.size);
            if (c != 0) return c;
            return Integer.compare(a.path.length(), b.path.length());
        });
    }

    public static boolean lossless(Song s) { return LOSSLESS.contains(s.extension()); }

    /** Lower-case, no accents, letters and digits only, single spaces. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n.equals("unknown") ? "" : n;
    }

    /** "FLAC · 1,024 kbps · 34.2 MB" */
    public static String quality(Song s) {
        StringBuilder sb = new StringBuilder(s.extension().toUpperCase(Locale.ROOT));
        if (s.bitrate > 0) sb.append(" · ").append(Math.round(s.bitrate / 1000.0)).append(" kbps");
        sb.append(" · ").append(String.format(Locale.ROOT, "%.1f MB", s.size / 1048576.0));
        sb.append(" · ").append(Library.time(s.duration));
        return sb.toString();
    }
}
