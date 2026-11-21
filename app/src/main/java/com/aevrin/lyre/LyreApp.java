/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Application;
import android.app.UiModeManager;

/** Holds the shared library, database and settings for activities and the service. */
public class LyreApp extends Application {
    private static LyreApp instance;
    private Library library;
    private Db db;
    private Prefs prefs;

    public static LyreApp get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        db = new Db(this);
        library = new Library(this, db, prefs);
        applyTheme(prefs.theme());
        org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true);
        // jaudiotagger logs every frame it reads at INFO; keep logcat quiet.
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(java.util.logging.Level.WARNING);
    }

    public Library library() { return library; }
    public Db db() { return db; }
    public Prefs prefs() { return prefs; }

    /** Dark by default. Applies to the whole app, including after restarts. */
    public void applyTheme(int theme) {
        UiModeManager ui = getSystemService(UiModeManager.class);
        if (ui == null) return;
        int mode = theme == Prefs.THEME_LIGHT ? UiModeManager.MODE_NIGHT_NO
                : theme == Prefs.THEME_SYSTEM ? UiModeManager.MODE_NIGHT_AUTO
                : UiModeManager.MODE_NIGHT_YES;
        ui.setApplicationNightMode(mode);
    }
}
