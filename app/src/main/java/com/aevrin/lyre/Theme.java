/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;

/**
 * Themes: dark (default), black (for OLED screens), light, or follow the system;
 * plus an accent color. Lyre's own views get their colors through
 * {@link Ui#color}, which asks {@link #override}; Android's built-in widgets
 * (switches, checkboxes, sliders) get the accent from a style overlay.
 */
final class Theme {
    private Theme() { }

    static final String[] THEME_NAMES = {"Dark", "Light", "Follow system", "Black (OLED)"};
    static final String[] ACCENT_NAMES = {"Aquamarine", "Blue", "Teal", "Indigo", "Purple", "Green"};
    // {dark-mode color, light-mode color}
    private static final int[][] ACCENTS = {
            {0xFF06B6D4, 0xFF0891B2}, {0xFF60A5FA, 0xFF2563EB}, {0xFF14B8A6, 0xFF0D9488},
            {0xFF818CF8, 0xFF4F46E5}, {0xFFA78BFA, 0xFF7C3AED}, {0xFF34D399, 0xFF059669},
    };
    private static final int[] STYLES = {
            R.style.Accent_Aqua, R.style.Accent_Blue, R.style.Accent_Teal,
            R.style.Accent_Indigo, R.style.Accent_Purple, R.style.Accent_Green,
    };

    /** Bumped on every change so open screens know to redraw. */
    static int version;

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static int accent(Context c) {
        int a = LyreApp.get().prefs().accent();
        if (a < 0 || a >= ACCENTS.length) a = 0;
        return ACCENTS[a][night(c) ? 0 : 1];
    }

    /** The themed color for a color resource, or null to use the resource as is. */
    static Integer override(Context c, int res) {
        if (res == R.color.accent) return accent(c);
        // Buttons use the accent too, darkened in dark themes so white text stays readable.
        if (res == R.color.button) return night(c) ? darken(accent(c), 0.25f) : accent(c);
        if (LyreApp.get().prefs().theme() == Prefs.THEME_BLACK && night(c)) {
            if (res == R.color.bg_primary) return 0xFF000000;
            if (res == R.color.bg_secondary) return 0xFF0B0B0B;
            if (res == R.color.surface) return 0xFF1C1C1C;
        }
        return null;
    }

    static int darken(int color, float by) {
        int r = Math.round(((color >> 16) & 0xFF) * (1 - by));
        int g = Math.round(((color >> 8) & 0xFF) * (1 - by));
        int b = Math.round((color & 0xFF) * (1 - by));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Call in every activity's onCreate, before building views. */
    static void apply(Activity a) {
        int acc = LyreApp.get().prefs().accent();
        a.getTheme().applyStyle(STYLES[acc >= 0 && acc < STYLES.length ? acc : 0], true);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(Ui.color(a, R.color.bg_primary)));
    }
}
