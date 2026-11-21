/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Volume normalization. Each track gets a gain so everything plays at about the
 * same loudness:
 * <ul>
 *   <li><b>Auto</b>: Lyre's own loudness measurement of the file (EBU R128), cached
 *       per file; ReplayGain tags are used until a track has been measured.</li>
 *   <li><b>ReplayGain track / album</b>: the file's tags, falling back to the
 *       measurement when a file has none.</li>
 * </ul>
 * The gain goes through Android's DynamicsProcessing effect on the player's
 * audio session, with a limiter so boosts can't clip. If the effect isn't
 * available, quiet tracks can't be boosted and loud ones are turned down with
 * the player volume instead.
 */
final class Normalizer {

    /** What was applied to the current track, for the Now Playing screen. */
    static final class Info {
        final float gainDb;
        final String source;
        Info(float gainDb, String source) { this.gainDb = gainDb; this.source = source; }
    }

    private static final float RG_REFERENCE = -18f; // LUFS that ReplayGain 2.0 gains aim for
    private static final float MAX_BOOST = 12f, MAX_CUT = -24f;

    private final Context ctx;
    private final ExoPlayer player;
    private final Db db;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService tagIo = Executors.newSingleThreadExecutor();
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "lyre-loudness");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pending = new HashSet<>();
    private final AtomicBoolean cancelAnalysis = new AtomicBoolean();
    private DynamicsProcessing dp;
    private Song current;
    private volatile Info info = new Info(0f, "Off");

    // Library scan ("Measure all")
    private volatile int bulkDone, bulkTotal;
    private volatile boolean bulkRunning;

    Normalizer(Context ctx, ExoPlayer player, Db db, Prefs prefs, int sessionId) {
        this.ctx = ctx;
        this.player = player;
        this.db = db;
        this.prefs = prefs;
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    false, 0, false, 0, false, 0, true).build();
            dp = new DynamicsProcessing(0, sessionId, cfg);
            dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                    true, true, 0, 1f, 60f, 10f, -1f, 0f));
            dp.setInputGainAllChannelsTo(0f);
            dp.setEnabled(true);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            android.util.Log.w("Lyre", "DynamicsProcessing unavailable; cut-only normalization", e);
            dp = null;
        }
    }

    Info info() { return info; }
    boolean canBoost() { return dp != null; }

    /** Works out and applies the gain for the song that just started. */
    void apply(Song s) {
        current = s;
        if (s == null || prefs.normMode() == Prefs.NORM_OFF) {
            set(0f);
            info = new Info(0f, prefs.normMode() == Prefs.NORM_OFF ? "Off" : "—");
            return;
        }
        // Apply what we know right away (cached), then refine off the main thread.
        Db.Gain cached = db.gain(s);
        decide(s, cached);
        if (cached == null || !cached.rgChecked) {
            tagIo.execute(() -> {
                Db.Gain g = db.gain(s);
                if (g == null) g = new Db.Gain();
                if (!g.rgChecked) {
                    float[] rg = Tags.replayGain(s);
                    g.rgTrack = rg[0];
                    g.rgAlbum = rg[1];
                    g.rgChecked = true;
                    db.putGain(s, g);
                }
                final Db.Gain fg = g;
                main.post(() -> { if (current == s) decide(s, fg); });
                if (prefs.normMode() == Prefs.NORM_AUTO && Float.isNaN(fg.lufs)) measure(s);
            });
        } else if (prefs.normMode() == Prefs.NORM_AUTO && Float.isNaN(cached.lufs)) {
            measure(s);
        }
    }

    /** Re-applies after a settings change. */
    void reapply() { apply(current); }

    /** Measures upcoming tracks in the background so they're ready when they play. */
    void prefetch(Song s) {
        if (s == null || prefs.normMode() == Prefs.NORM_OFF || !prefs.analyzeAhead()) return;
        tagIo.execute(() -> {
            Db.Gain g = db.gain(s);
            if (g == null) g = new Db.Gain();
            if (!g.rgChecked) {
                float[] rg = Tags.replayGain(s);
                g.rgTrack = rg[0];
                g.rgAlbum = rg[1];
                g.rgChecked = true;
                db.putGain(s, g);
            }
            if (Float.isNaN(g.lufs) && prefs.normMode() == Prefs.NORM_AUTO) measure(s);
        });
    }

    private void decide(Song s, Db.Gain g) {
        int mode = prefs.normMode();
        float target = prefs.normTarget();
        float offset = target - RG_REFERENCE;
        float gain;
        String src;
        boolean measured = g != null && !Float.isNaN(g.lufs);
        boolean rgT = g != null && !Float.isNaN(g.rgTrack);
        boolean rgA = g != null && !Float.isNaN(g.rgAlbum);
        if (mode == Prefs.NORM_ALBUM && rgA) {
            gain = g.rgAlbum + offset; src = "ReplayGain album";
        } else if ((mode == Prefs.NORM_TRACK || mode == Prefs.NORM_ALBUM) && rgT) {
            gain = g.rgTrack + offset; src = "ReplayGain track";
        } else if (measured) {
            gain = target - g.lufs; src = String.format(java.util.Locale.ROOT, "Measured %.1f LUFS", g.lufs);
        } else if (rgT) {
            gain = g.rgTrack + offset; src = "ReplayGain track";
        } else {
            gain = prefs.fallbackGain(); src = mode == Prefs.NORM_AUTO ? "Default (measuring…)" : "Default (no tags)";
        }
        gain += prefs.preamp();
        gain = Math.max(MAX_CUT, Math.min(MAX_BOOST, gain));
        if (dp == null && gain > 0) { src += ", can't boost"; gain = 0; }
        set(gain);
        info = new Info(gain, src);
    }

    private void set(float db) {
        if (dp != null) {
            try {
                dp.setInputGainAllChannelsTo(db);
                player.setVolume(1f);
                return;
            } catch (RuntimeException e) {
                android.util.Log.w("Lyre", "DynamicsProcessing failed; falling back to volume", e);
                dp = null;
            }
        }
        player.setVolume((float) Math.min(1.0, Math.pow(10.0, db / 20.0)));
    }

    private void measure(Song s) {
        synchronized (pending) {
            if (!pending.add(s.path)) return;
        }
        analyzer.execute(() -> {
            try {
                Db.Gain g = db.gain(s);
                if (g != null && !Float.isNaN(g.lufs)) return;
                float[] r = Loudness.measure(ctx, s, cancelAnalysis);
                if (r == null) return;
                if (g == null) g = new Db.Gain();
                g.lufs = r[0];
                g.peak = r[1];
                db.putGain(s, g);
                final Db.Gain fg = g;
                main.post(() -> { if (current == s) decide(s, fg); });
            } finally {
                synchronized (pending) { pending.remove(s.path); }
            }
        });
    }

    // ---------------------------------------------------------------- measure all

    boolean bulkRunning() { return bulkRunning; }
    int bulkDone() { return bulkDone; }
    int bulkTotal() { return bulkTotal; }

    /** Measures every unmeasured song in the library, one at a time in the background. */
    void measureAll(java.util.List<Song> songs) {
        if (bulkRunning) return;
        ArrayDeque<Song> todo = new ArrayDeque<>();
        for (Song s : songs) todo.add(s);
        bulkRunning = true;
        bulkDone = 0;
        bulkTotal = todo.size();
        cancelAnalysis.set(false);
        analyzer.execute(() -> {
            try {
                while (!todo.isEmpty() && !cancelAnalysis.get()) {
                    Song s = todo.poll();
                    Db.Gain g = db.gain(s);
                    if (g == null || Float.isNaN(g.lufs)) {
                        float[] r = Loudness.measure(ctx, s, cancelAnalysis);
                        if (r != null) {
                            if (g == null) g = new Db.Gain();
                            g.lufs = r[0];
                            g.peak = r[1];
                            db.putGain(s, g);
                        }
                    }
                    bulkDone++;
                }
            } finally {
                bulkRunning = false;
                cancelAnalysis.set(false);
                main.post(this::reapply);
            }
        });
    }

    void cancelBulk() { if (bulkRunning) cancelAnalysis.set(true); }

    void release() {
        cancelAnalysis.set(true);
        analyzer.shutdownNow();
        tagIo.shutdownNow();
        if (dp != null) {
            try { dp.release(); } catch (RuntimeException ignored) { }
            dp = null;
        }
    }
}
