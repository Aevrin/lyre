/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import java.util.Locale;

/** Ten-band equalizer settings: band frequencies and presets. */
public final class Eq {
    private Eq() { }

    public static final int BANDS = 10;
    /** Band centers in Hz (ISO octaves). */
    public static final int[] CENTER = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    /** Upper edge of each band, which is what DynamicsProcessing takes. */
    public static final float[] CUTOFF = {44, 88, 177, 355, 710, 1420, 2840, 5680, 11360, 20000};
    public static final float MAX_DB = 12f;

    public static final String[] PRESET_NAMES = {"Flat", "Bass boost", "Treble boost", "Bass and treble", "Vocal",
            "Rock", "Pop", "Jazz", "Classical", "Electronic", "Phone speaker"};
    private static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 5, 4, 2, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 1, 2, 4, 5, 6},
            {5, 4, 2, 0, -1, -1, 0, 2, 4, 5},
            {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1},
            {4, 3, 2, 0, -1, -1, 1, 2, 3, 4},
            {-1, 0, 2, 3, 4, 3, 1, 0, -1, -1},
            {3, 2, 1, 2, -1, -1, 0, 1, 2, 3},
            {4, 3, 2, 1, -1, -1, 0, 2, 3, 4},
            {5, 4, 1, 0, -2, 1, 0, 1, 4, 5},
            {-6, -4, -2, 0, 2, 3, 3, 2, 0, -1},
    };

    public static float[] preset(int i) {
        return PRESETS[Math.max(0, Math.min(i, PRESETS.length - 1))].clone();
    }

    /** Index of the preset matching these gains, or -1 for custom. */
    public static int match(float[] g) {
        for (int p = 0; p < PRESETS.length; p++) {
            boolean same = true;
            for (int i = 0; i < BANDS; i++) if (Math.abs(PRESETS[p][i] - g[i]) > 0.01f) { same = false; break; }
            if (same) return p;
        }
        return -1;
    }

    /** Headroom to take off the input so boosted bands don't drive the limiter hard. */
    public static float headroom(float[] g) {
        float max = 0;
        for (float x : g) max = Math.max(max, x);
        return max / 2f;
    }

    public static String label(int hz) {
        return hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
    }

    public static String encode(float[] g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.1f", g[i]));
        }
        return sb.toString();
    }

    public static float[] decode(String s) {
        float[] g = new float[BANDS];
        if (s == null || s.isEmpty()) return g;
        String[] p = s.split(",");
        for (int i = 0; i < BANDS && i < p.length; i++) {
            try { g[i] = Math.max(-MAX_DB, Math.min(MAX_DB, Float.parseFloat(p[i]))); }
            catch (NumberFormatException ignored) { }
        }
        return g;
    }
}
