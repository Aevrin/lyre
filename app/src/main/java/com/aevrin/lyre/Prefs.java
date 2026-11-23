/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.Context;
import android.content.SharedPreferences;

/** App settings in one SharedPreferences file. */
public final class Prefs {
    // Normalization modes
    public static final int NORM_OFF = 0, NORM_AUTO = 1, NORM_TRACK = 2, NORM_ALBUM = 3;
    // Theme
    public static final int THEME_DARK = 0, THEME_LIGHT = 1, THEME_SYSTEM = 2, THEME_BLACK = 3;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("lyre", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return sp; }

    public boolean blacklistOn() { return sp.getBoolean("blacklist_on", true); }
    public void blacklistOn(boolean v) { sp.edit().putBoolean("blacklist_on", v).apply(); }

    /** Off / auto (measure loudness, prefer ReplayGain tags) / ReplayGain track / album. */
    public int normMode() { return sp.getInt("norm_mode", NORM_AUTO); }
    public void normMode(int v) { sp.edit().putInt("norm_mode", v).apply(); }

    /** Target loudness in LUFS. ReplayGain 2.0's reference is -18. */
    public float normTarget() { return sp.getFloat("norm_target", -18f); }
    public void normTarget(float v) { sp.edit().putFloat("norm_target", v).apply(); }

    /** Extra gain in dB applied on top of normalization. */
    public float preamp() { return sp.getFloat("preamp", 0f); }
    public void preamp(float v) { sp.edit().putFloat("preamp", v).apply(); }

    /** Gain in dB for tracks with no ReplayGain tag and no measurement yet. */
    public float fallbackGain() { return sp.getFloat("fallback_gain", -6f); }
    public void fallbackGain(float v) { sp.edit().putFloat("fallback_gain", v).apply(); }

    /** Measure tracks in the background as they come up in the queue. */
    public boolean analyzeAhead() { return sp.getBoolean("analyze_ahead", true); }
    public void analyzeAhead(boolean v) { sp.edit().putBoolean("analyze_ahead", v).apply(); }

    /** Only files the scanner marks as music (skips ringtones, recordings...). */
    public boolean musicOnly() { return sp.getBoolean("music_only", true); }
    public void musicOnly(boolean v) { sp.edit().putBoolean("music_only", v).apply(); }

    /** Hide tracks shorter than this many seconds (0 = show all). */
    public int minSeconds() { return sp.getInt("min_seconds", 30); }
    public void minSeconds(int v) { sp.edit().putInt("min_seconds", v).apply(); }

    /** M3U export: paths relative to the playlist file when possible. */
    public boolean m3uRelative() { return sp.getBoolean("m3u_relative", true); }
    public void m3uRelative(boolean v) { sp.edit().putBoolean("m3u_relative", v).apply(); }

    public int theme() { return sp.getInt("theme", THEME_DARK); }
    public void theme(int v) { sp.edit().putInt("theme", v).apply(); }

    public int accent() { return sp.getInt("accent", 0); }
    public void accent(int v) { sp.edit().putInt("accent", v).apply(); }

    public boolean eqOn() { return sp.getBoolean("eq_on", false); }
    public void eqOn(boolean v) { sp.edit().putBoolean("eq_on", v).apply(); }
    public float[] eqGains() { return Eq.decode(sp.getString("eq_gains", "")); }
    public void eqGains(float[] g) { sp.edit().putString("eq_gains", Eq.encode(g)).apply(); }

    /** Text size multiplier for Lyre's lists and screens. */
    public float textScale() { return sp.getFloat("text_scale", 1f); }
    public void textScale(float v) { sp.edit().putFloat("text_scale", v).apply(); }

    /** Which library tabs show, as a bit set over MainActivity's tab order (all by default). */
    public int tabsShown() { return sp.getInt("tabs_shown", 0x3F); }
    public void tabsShown(int v) { sp.edit().putInt("tabs_shown", v).apply(); }

    public int tab() { return sp.getInt("tab", 0); }
    public void tab(int v) { sp.edit().putInt("tab", v).apply(); }

    /** Hide tracks longer than this many minutes (0 = no limit). */
    public int maxMinutes() { return sp.getInt("max_minutes", 0); }
    public void maxMinutes(int v) { sp.edit().putInt("max_minutes", v).apply(); }

    /** Sort directions: false = the natural one (A→Z, newest first), true = reversed. */
    public boolean songSortReversed() { return sp.getBoolean("song_sort_rev", false); }
    public void songSortReversed(boolean v) { sp.edit().putBoolean("song_sort_rev", v).apply(); }
    public boolean albumSortReversed() { return sp.getBoolean("album_sort_rev", false); }
    public void albumSortReversed(boolean v) { sp.edit().putBoolean("album_sort_rev", v).apply(); }

    public int songSort() { return sp.getInt("song_sort", 0); }
    public void songSort(int v) { sp.edit().putInt("song_sort", v).apply(); }

    public int albumSort() { return sp.getInt("album_sort", 0); }
    public void albumSort(int v) { sp.edit().putInt("album_sort", v).apply(); }

    // Saved queue for resuming after the service is killed.
    public String savedQueue() { return sp.getString("q_ids", ""); }
    public int savedIndex() { return sp.getInt("q_index", 0); }
    public long savedPosition() { return sp.getLong("q_pos", 0); }
    public void saveQueue(String ids, int index, long pos) {
        sp.edit().putString("q_ids", ids).putInt("q_index", index).putLong("q_pos", pos).apply();
    }
    public boolean savedShuffle() { return sp.getBoolean("q_shuffle", false); }
    public int savedRepeat() { return sp.getInt("q_repeat", 0); }
    public void saveModes(boolean shuffle, int repeat) {
        sp.edit().putBoolean("q_shuffle", shuffle).putInt("q_repeat", repeat).apply();
    }
}
