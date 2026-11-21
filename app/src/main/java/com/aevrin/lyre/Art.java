/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Album art thumbnails from MediaStore, cached in memory. */
final class Art {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final java.util.Set<String> missing = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final ExecutorService exec = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Art() { }

    /** Loads a song's art into an ImageView, or shows the placeholder icon. Safe in recycled rows. */
    static void into(ImageView v, Song s, int sizePx, int placeholder) {
        if (s == null) { placeholder(v, placeholder); v.setTag(R.id.art_key, null); return; }
        // List thumbnails are shared by an album's songs; big art is per song.
        String key = sizePx <= 256 ? "a" + s.albumId + ":" + sizePx : "s" + s.id + ":" + sizePx;
        v.setTag(R.id.art_key, key);
        Bitmap b = cache.get(key);
        if (b != null) { show(v, b); return; }
        placeholder(v, placeholder);
        if (missing.contains(key)) return;
        final String k = key;
        final Context ctx = v.getContext().getApplicationContext();
        exec.execute(() -> {
            Bitmap bm = null;
            try {
                bm = ctx.getContentResolver().loadThumbnail(s.uri(), new Size(sizePx, sizePx), null);
            } catch (Exception ignored) { }
            final Bitmap res = bm;
            if (res == null) missing.add(k); else cache.put(k, res);
            main.post(() -> {
                if (k.equals(v.getTag(R.id.art_key)) && res != null) show(v, res);
            });
        });
    }

    static void forget() {
        cache.evictAll();
        missing.clear();
    }

    private static void show(ImageView v, Bitmap b) {
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageTintList(null);
        v.setImageBitmap(b);
    }

    private static void placeholder(ImageView v, int res) {
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setImageResource(res);
    }
}
