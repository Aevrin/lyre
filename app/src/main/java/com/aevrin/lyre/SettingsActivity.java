/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** All settings on one scrolling page, built in code. Changes apply immediately. */
public class SettingsActivity extends Activity implements Library.Listener {

    private int themeVersion;

    private static final int REQ_BACKUP = 21, REQ_RESTORE = 22;
    private Prefs prefs;
    private Db db;
    private Library library;
    private Remote remote;
    private LinearLayout content;
    private android.widget.Button blacklistButton;
    private TextView blacklistStatus, measureStatus;
    private android.widget.Button measureButton;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateMeasureStatus();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        LyreApp app = (LyreApp) getApplication();
        prefs = app.prefs();
        db = app.db();
        library = app.library();
        remote = new Remote(this, null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);
        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        top.addView(Ui.text(this, "Settings", 20, R.color.text_primary, true));
        root.addView(top, Ui.matchWrap());
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(this, 32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        buildAll();
        library.addListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Keeps the playback service (which owns the loudness scanner) running while here.
        remote.connect(null);
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
        remote.release();
    }

    @Override
    protected void onDestroy() {
        library.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onLibraryChanged() { updateBlacklistStatus(); }

    @Override
    protected void onRestart() {
        super.onRestart();
        updateBlacklistStatus();   // back from the blacklist screen
    }

    // ---------------------------------------------------------------- page

    private void buildAll() {
        content.removeAllViews();

        section("Sound");
        LinearLayout eqb = buttons();
        eqb.addView(Ui.button(this, "Equalizer" + (prefs.eqOn() ? " (on)" : ""),
                v -> startActivity(new Intent(this, EqualizerActivity.class))));
        content.addView(eqb);

        section("Volume normalization");
        String[] modes = {"Off", "Auto (measure loudness)", "ReplayGain: track", "ReplayGain: album"};
        int[] modeVals = {Prefs.NORM_OFF, Prefs.NORM_AUTO, Prefs.NORM_TRACK, Prefs.NORM_ALBUM};
        choice("Mode", modes, indexOf(modeVals, prefs.normMode()),
                "Auto measures each track the first time it plays (or all at once below) and evens out the volume. "
                        + "ReplayGain uses tags written by a tagger; tracks without tags fall back to the measurement.",
                w -> { prefs.normMode(modeVals[w]); reapply(); });
        float[] targets = {-14f, -16f, -18f, -20f, -23f};
        String[] targetNames = {"-14 LUFS (loud, like streaming)", "-16 LUFS", "-18 LUFS (ReplayGain standard)", "-20 LUFS", "-23 LUFS (quiet, broadcast)"};
        choice("Target loudness", targetNames, indexOf(targets, prefs.normTarget()),
                "Louder targets need boosting quiet tracks; Lyre's limiter keeps peaks from clipping.",
                w -> { prefs.normTarget(targets[w]); reapply(); });
        float[] pre = {-6f, -3f, 0f, 3f, 6f};
        String[] preNames = {"-6 dB", "-3 dB", "0 dB", "+3 dB", "+6 dB"};
        choice("Pre-amp", preNames, indexOf(pre, prefs.preamp()), "Added on top of every track's gain.",
                w -> { prefs.preamp(pre[w]); reapply(); });
        float[] fb = {0f, -3f, -6f, -9f};
        String[] fbNames = {"0 dB", "-3 dB", "-6 dB", "-9 dB"};
        choice("Unmeasured tracks", fbNames, indexOf(fb, prefs.fallbackGain()),
                "Gain for a track with no tags that hasn't been measured yet (most modern music is loud, so a cut is safer).",
                w -> { prefs.fallbackGain(fb[w]); reapply(); });
        toggle("Measure upcoming tracks", "Measures the next track in the queue in the background.",
                prefs.analyzeAhead(), prefs::analyzeAhead);
        measureStatus = note("");
        LinearLayout mb = buttons();
        measureButton = Ui.button(this, "Measure whole library", v -> {
            PlaybackService svc = PlaybackService.get();
            if (svc == null) { Ui.toast(this, "Playback service isn't ready yet; try again"); return; }
            if (svc.measuring()) svc.cancelMeasureAll(); else svc.measureAll();
            updateMeasureStatus();
        });
        mb.addView(measureButton);
        mb.addView(gap());
        mb.addView(Ui.button(this, "Forget measurements", v -> Ui.confirm(this, "Forget measurements?",
                "Every track will be measured again when it next plays.", "Forget", () -> {
                    db.clearGains();
                    reapply();
                    updateMeasureStatus();
                })));
        content.addView(mb);
        updateMeasureStatus();

        section("Blacklist");
        toggle("Use blacklist", "Switching it off shows everything again but keeps the list.",
                prefs.blacklistOn(), v -> {
                    prefs.blacklistOn(v);
                    library.refilter();
                    updateBlacklistStatus();
                });
        blacklistStatus = note("");
        LinearLayout bb = buttons();
        blacklistButton = Ui.button(this, "Manage blacklist", v -> startActivity(new Intent(this, BlacklistActivity.class)));
        bb.addView(blacklistButton);
        content.addView(bb);
        updateBlacklistStatus();

        section("Library");
        toggle("Music only", "Skip files the media scanner doesn't mark as music (ringtones, recordings, notifications).",
                prefs.musicOnly(), v -> { prefs.musicOnly(v); library.reload(); });
        int[] mins = {0, 10, 30, 60};
        String[] minNames = {"Show all", "10 seconds", "30 seconds", "1 minute"};
        choice("Hide tracks shorter than", minNames, indexOf(mins, prefs.minSeconds()), null,
                w -> { prefs.minSeconds(mins[w]); library.reload(); });
        int[] maxes = {0, 10, 15, 20, 30, 60};
        String[] maxNames = {"No limit", "10 minutes", "15 minutes", "20 minutes", "30 minutes", "1 hour"};
        choice("Hide tracks longer than", maxNames, indexOf(maxes, prefs.maxMinutes()),
                "For keeping long mixes, audiobooks or recordings out of the library.",
                w -> { prefs.maxMinutes(maxes[w]); library.reload(); });
        LinearLayout pc = buttons();
        pc.addView(Ui.button(this, "Find duplicates", v -> startActivity(new Intent(this, DuplicatesActivity.class))));
        content.addView(pc);
        pc = buttons();
        pc.addView(Ui.button(this, "Find unplayable files", v -> startActivity(new Intent(this, UnplayableActivity.class))));
        pc.addView(gap());
        pc.addView(Ui.button(this, "Reset play counts", v -> Ui.confirm(this, "Reset play counts?",
                "Play counts, skips and last-played dates are cleared. Dynamic playlists that use them will change.",
                "Reset", () -> { db.clearStats(); Ui.toast(this, "Play counts reset"); })));
        content.addView(pc);
        note("Lyre uses Android's media scanner, so new files appear once Android has found them. "
                + "Tap the Lyre title on the main screen to reload.");

        section("Playlists");
        toggle("Relative paths in M3U", "Paths relative to where the playlist is saved, so it still works if the "
                + "whole music folder moves or syncs to another device. Off writes full paths.",
                prefs.m3uRelative(), prefs::m3uRelative);

        section("Backup");
        note("Saves playlists, dynamic playlists, the blacklist, favorites, play counts, loudness measurements "
                + "and settings to one file. Keeping it in a synced folder means it survives a reinstall or a new phone.");
        LinearLayout bk = buttons();
        bk.addView(Ui.button(this, "Back up…", v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "lyre-backup-" + java.time.LocalDate.now() + ".json"), REQ_BACKUP)));
        bk.addView(gap());
        bk.addView(Ui.button(this, "Restore…", v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQ_RESTORE)));
        content.addView(bk);

        section("Appearance");
        choice("Theme", Theme.THEME_NAMES, prefs.theme(), "Black uses true black backgrounds, which saves power on OLED screens.", w -> {
            prefs.theme(w);
            ((LyreApp) getApplication()).applyTheme(w);
            recreate();
        });
        choice("Accent color", Theme.ACCENT_NAMES, prefs.accent(), "Used for highlights, buttons and the playing song.", w -> {
            prefs.accent(w);
            Theme.version++;
            recreate();
        });

        section("About");
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        note("Lyre " + version + "\nVibe-coded: written largely by an AI model (Claude, by Anthropic) under human "
                + "direction and testing. Free software under the GNU GPL v3 or later. No ads, no tracking, no internet access.");
        LinearLayout ab = buttons();
        ab.addView(Ui.button(this, "Source code", v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Aevrin/lyre")))));
        content.addView(ab);
    }

    private void reapply() {
        PlaybackService svc = PlaybackService.get();
        if (svc != null) svc.reapplyGain();
    }

    private void updateMeasureStatus() {
        if (measureStatus == null) return;
        PlaybackService svc = PlaybackService.get();
        String s = db.measuredCount() + " of " + library.songs().size() + " tracks measured. "
                + "Measured tracks are skipped; a track is measured again only if its file changes.";
        if (svc != null && svc.measuring()) {
            s = svc.measureTotal() == 0 && svc.measureDone() == 0 ? "Measuring…"
                    : "Measuring " + svc.measureDone() + " / " + svc.measureTotal() + " still to do";
            String cur = svc.measureCurrent();
            if (!cur.isEmpty()) s += "\nNow: " + cur;
            if (svc.measureFailed() > 0) s += "\n" + svc.measureFailed() + " couldn't be measured (skipped)";
            s += "\nTap the button again to stop. Keeps going while Lyre is open or playing.";
        } else if (svc != null && svc.measureFailed() > 0) {
            s += " " + svc.measureFailed() + " couldn't be measured last run (Settings → Find unplayable files may show why).";
        }
        if (svc != null && !svc.canBoost()) s += "\nThis device can't boost volume, so quiet tracks are left as they are.";
        measureStatus.setText(s);
        if (measureButton != null) {
            measureButton.setText(svc != null && svc.measuring() ? "Stop measuring" : "Measure whole library");
        }
    }

    // ---------------------------------------------------------------- blacklist

    private void updateBlacklistStatus() {
        if (blacklistStatus == null) return;
        List<Db.BlEntry> list = db.blacklist();
        java.util.Map<String, Integer> byType = new java.util.LinkedHashMap<>();
        for (Db.BlEntry e : list) byType.merge(e.type, 1, Integer::sum);
        StringBuilder parts = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : byType.entrySet()) {
            if (parts.length() > 0) parts.append(", ");
            parts.append(BlacklistActivity.count(e.getValue(), e.getKey()));
        }
        String what = list.isEmpty() ? "Empty. Blacklist songs, albums, artists, genres or folders from their ⋮ menu."
                : Library.plural(list.size(), "entry").replace("entrys", "entries") + " (" + parts + ").";
        String state = !prefs.blacklistOn() ? " Off: nothing hidden."
                : list.isEmpty() ? "" : " Hiding " + Library.plural(library.hiddenCount(), "song") + ".";
        blacklistStatus.setText(what + state);
        if (blacklistButton != null) blacklistButton.setText(list.isEmpty() ? "Manage blacklist" : "Manage blacklist (" + list.size() + ")");
    }

    // ---------------------------------------------------------------- row builders

    private void section(String title) {
        TextView t = Ui.text(this, title, 14, R.color.accent, true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 4));
        content.addView(t, Ui.matchWrap());
    }

    private TextView note(String s) {
        TextView t = Ui.text(this, s, 13, R.color.text_muted, false);
        t.setSingleLine(false);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
        content.addView(t, Ui.matchWrap());
        return t;
    }

    private LinearLayout buttons() {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        return l;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        return v;
    }

    interface BoolSetter { void set(boolean v); }

    private void toggle(String title, String sub, boolean value, BoolSetter set) {
        LinearLayout row = new LinearLayout(this);
        Ui.center(row);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        row.setBackground(Ui.ripple(this, false));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 16, R.color.text_primary, false));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 13, R.color.text_muted, false);
            s.setSingleLine(false);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, v) -> set.set(v));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        content.addView(row, Ui.matchWrap());
    }

    private void choice(String title, String[] options, int selected, String sub, java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        row.setBackground(Ui.ripple(this, false));
        row.addView(Ui.text(this, title, 16, R.color.text_primary, false));
        TextView val = Ui.text(this, selected >= 0 && selected < options.length ? options[selected] : "—", 14, R.color.accent, false);
        row.addView(val);
        if (sub != null) {
            TextView s = Ui.text(this, sub, 13, R.color.text_muted, false);
            s.setSingleLine(false);
            row.addView(s);
        }
        final int[] cur = {selected};
        row.setOnClickListener(v -> Ui.choose(this, title, options, cur[0], w -> {
            cur[0] = w;
            val.setText(options[w]);
            pick.accept(w);
        }));
        content.addView(row, Ui.matchWrap());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_BACKUP) {
            new Thread(() -> {
                String msg;
                try {
                    Backup.write(getContentResolver(), uri, db, prefs);
                    msg = "Backed up";
                } catch (Exception e) {
                    msg = "Backup failed: " + e.getMessage();
                }
                final String m = msg;
                main.post(() -> Ui.toast(this, m));
            }).start();
        } else if (req == REQ_RESTORE) {
            org.json.JSONObject root;
            try {
                root = Backup.read(getContentResolver(), uri);
            } catch (Exception e) {
                Ui.toast(this, "Can't restore: " + e.getMessage());
                return;
            }
            final org.json.JSONObject backup = root;
            Ui.confirm(this, "Restore this backup?", "It contains " + Backup.describe(backup) + ".\n\n"
                    + "Lyre's current playlists, blacklist, favorites, play counts and settings are replaced. "
                    + "Music files aren't touched.", "Restore", () -> {
                try {
                    Backup.restore(backup, db, prefs);
                } catch (Exception e) {
                    Ui.toast(this, "Restore failed: " + e.getMessage());
                    return;
                }
                LyreApp app = (LyreApp) getApplication();
                app.applyTheme(prefs.theme());
                library.reload();
                PlaybackService svc = PlaybackService.get();
                if (svc != null) { svc.applyEq(); svc.reapplyGain(); }
                Ui.toast(this, "Restored");
                recreate();
            });
        }
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    private static int indexOf(float[] a, float v) {
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - v) < 0.01f) return i;
        return -1;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Theme or accent changed in Settings while this screen was open.
        if (themeVersion != Theme.version) recreate();
    }
}
