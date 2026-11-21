/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3U export and import through Android's file picker, so no storage permission
 * is needed for the playlist files themselves. Files are UTF-8 with #EXTINF lines.
 */
public final class PlaylistIo {
    private PlaylistIo() { }

    public static final String MIME = "audio/x-mpegurl";

    /** Writes songs as M3U to a picked document. Relative paths when the folder is known. */
    public static void export(ContentResolver cr, Uri doc, List<Song> songs, boolean relative) throws IOException {
        String dir = relative ? dirOfDocument(doc) : null;
        try (OutputStream os = cr.openOutputStream(doc, "wt")) {
            if (os == null) throw new IOException("Can't open " + doc);
            write(os, songs, dir);
        }
    }

    /** Creates name.m3u inside a picked folder (tree) and writes it. Returns the new document. */
    public static Uri exportToTree(ContentResolver cr, Uri tree, String name, List<Song> songs,
                                   boolean relative) throws IOException {
        String treeDocId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId);
        Uri doc = DocumentsContract.createDocument(cr, parent, MIME, safeName(name) + ".m3u");
        if (doc == null) throw new IOException("Couldn't create " + name + ".m3u");
        String dir = relative ? pathOfDocId(treeDocId) : null;
        try (OutputStream os = cr.openOutputStream(doc, "wt")) {
            if (os == null) throw new IOException("Can't open " + doc);
            write(os, songs, dir);
        }
        return doc;
    }

    static void write(OutputStream os, List<Song> songs, String dir) throws IOException {
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        w.write("#EXTM3U\n");
        for (Song s : songs) {
            w.write("#EXTINF:" + (s.duration / 1000) + "," + oneLine(s.artist) + " - " + oneLine(s.title) + "\n");
            w.write((dir != null ? relativize(dir, s.path) : s.path) + "\n");
        }
        w.flush();
    }

    /** Result of reading an M3U: paths that matched songs, and how many lines didn't. */
    public static final class Imported {
        public final List<String> paths = new ArrayList<>();
        public int missing;
    }

    public static Imported read(ContentResolver cr, Uri doc, List<Song> library) throws IOException {
        String dir = dirOfDocument(doc);
        Map<String, Song> exact = new HashMap<>();
        Map<String, Song> tail = new HashMap<>();   // "parent/file.ext" lower-cased
        Map<String, Song> file = new HashMap<>();   // "file.ext" lower-cased
        for (Song s : library) {
            exact.put(s.path, s);
            tail.put(tail2(s.path), s);
            file.putIfAbsent(s.fileName().toLowerCase(Locale.ROOT), s);
        }
        Imported out = new Imported();
        try (InputStream is = cr.openInputStream(doc)) {
            if (is == null) throw new IOException("Can't open " + doc);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.replace("﻿", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("file://")) line = Uri.decode(line.substring(7));
                line = line.replace('\\', '/');
                String abs = line.startsWith("/") ? normalize(line)
                        : dir != null ? normalize(dir + "/" + line) : null;
                Song s = abs != null ? exact.get(abs) : null;
                if (s == null) s = tail.get(tail2(line));
                if (s == null) s = file.get(Library.lastSegment(line).toLowerCase(Locale.ROOT));
                if (s != null) out.paths.add(s.path); else out.missing++;
            }
        }
        return out;
    }

    public static String nameFromDocument(ContentResolver cr, Uri doc) {
        String n = null;
        try (android.database.Cursor c = cr.query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (n == null) n = "Imported";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ---------------------------------------------------------------- paths

    /** Filesystem folder of a document from the system storage provider, or null. */
    static String dirOfDocument(Uri doc) {
        try {
            if (!"com.android.externalstorage.documents".equals(doc.getAuthority())) return null;
            String p = pathOfDocId(DocumentsContract.getDocumentId(doc));
            if (p == null) return null;
            int slash = p.lastIndexOf('/');
            return slash > 0 ? p.substring(0, slash) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "primary:Music/x" → /storage/emulated/0/Music/x; "1234-ABCD:x" → /storage/1234-ABCD/x. */
    static String pathOfDocId(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String vol = docId.substring(0, colon), rest = docId.substring(colon + 1);
        String root = "primary".equals(vol) ? "/storage/emulated/0" : "/storage/" + vol;
        return rest.isEmpty() ? root : root + "/" + rest;
    }

    static String relativize(String dir, String path) {
        String[] d = split(dir), p = split(path);
        int i = 0;
        while (i < d.length && i < p.length && d[i].equals(p[i])) i++;
        if (i < 2) return path; // different volume (only "/storage" in common)
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < d.length; k++) sb.append("../");
        for (int k = i; k < p.length; k++) {
            sb.append(p[k]);
            if (k < p.length - 1) sb.append('/');
        }
        return sb.toString();
    }

    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String s : path.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); continue; }
            parts.addLast(s);
        }
        return "/" + String.join("/", parts);
    }

    private static String[] split(String p) {
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    private static String tail2(String path) {
        String p = path.replace('\\', '/');
        int last = p.lastIndexOf('/');
        int prev = last > 0 ? p.lastIndexOf('/', last - 1) : -1;
        return p.substring(prev + 1).toLowerCase(Locale.ROOT);
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    public static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "playlist" : n;
    }
}
