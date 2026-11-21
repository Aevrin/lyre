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

    private Prefs prefs;
    private Db db;
    private Library library;
    private Remote remote;
    private LinearLayout content;
    private LinearLayout blacklistBox;
    private TextView blacklistStatus, measureStatus;
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

    // ---------------------------------------------------------------- page

    private void buildAll() {
        content.removeAllViews();

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
        mb.addView(Ui.button(this, "Measure whole library", v -> {
            PlaybackService svc = PlaybackService.get();
            if (svc == null) { Ui.toast(this, "Playback service isn't ready yet; try again"); return; }
            if (svc.measuring()) svc.cancelMeasureAll(); else svc.measureAll();
            updateMeasureStatus();
        }));
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
        blacklistBox = new LinearLayout(this);
        blacklistBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(blacklistBox, Ui.matchWrap());
        LinearLayout bb = buttons();
        bb.addView(Ui.button(this, "Add folder…", v -> addFolder()));
        bb.addView(gap());
        bb.addView(Ui.button(this, "Clear all", v -> Ui.confirm(this, "Clear the blacklist?",
                "Every entry is removed. To show everything but keep the list, switch it off instead.", "Clear", () -> {
                    db.clearBlacklist();
                    library.refilter();
                    renderBlacklist();
                })));
        content.addView(bb);
        renderBlacklist();

        section("Library");
        toggle("Music only", "Skip files the media scanner doesn't mark as music (ringtones, recordings, notifications).",
                prefs.musicOnly(), v -> { prefs.musicOnly(v); library.reload(); });
        int[] mins = {0, 10, 30, 60};
        String[] minNames = {"Show all", "10 seconds", "30 seconds", "1 minute"};
        choice("Hide tracks shorter than", minNames, indexOf(mins, prefs.minSeconds()), null,
                w -> { prefs.minSeconds(mins[w]); library.reload(); });
        LinearLayout pc = buttons();
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

        section("Appearance");
        String[] themes = {"Dark", "Light", "Follow system"};
        choice("Theme", themes, prefs.theme(), null, w -> {
            prefs.theme(w);
            ((LyreApp) getApplication()).applyTheme(w);
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
        String s = db.measuredCount() + " of " + library.songs().size() + " tracks measured.";
        if (svc != null && svc.measuring()) {
            s = "Measuring… " + svc.measureDone() + " / " + svc.measureTotal()
                    + " (tap again to stop; keeps going while Lyre is open or playing)";
        }
        if (svc != null && !svc.canBoost()) s += "\nThis device can't boost volume, so quiet tracks are left as they are.";
        measureStatus.setText(s);
    }

    // ---------------------------------------------------------------- blacklist

    private void renderBlacklist() {
        blacklistBox.removeAllViews();
        List<Db.BlEntry> list = db.blacklist();
        if (list.isEmpty()) {
            TextView t = note("Empty. Blacklist songs, albums, artists, genres or folders from their ⋮ menu.");
            ((ViewGroup) t.getParent()).removeView(t);
            blacklistBox.addView(t);
        }
        for (Db.BlEntry e : list) {
            LinearLayout row = new LinearLayout(this);
            Ui.center(row);
            row.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 8), 0);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(this, e.label == null ? e.value : e.label, 15, R.color.text_primary, false));
            texts.addView(Ui.text(this, capital(e.type), 12, R.color.text_muted, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.icon(this, R.drawable.ic_close, "Remove", v -> {
                db.removeBlacklist(e.id);
                library.refilter();
                renderBlacklist();
            }));
            blacklistBox.addView(row, Ui.matchWrap());
        }
        updateBlacklistStatus();
    }

    private void updateBlacklistStatus() {
        if (blacklistStatus == null) return;
        int n = db.blacklist().size();
        if (!prefs.blacklistOn()) {
            blacklistStatus.setText("Off: " + Library.plural(n, "entry").replace("entrys", "entries") + " kept, nothing hidden.");
        } else {
            blacklistStatus.setText("Hiding " + Library.plural(library.hiddenCount(), "song") + ".");
        }
    }

    /** Pick from the folders that hold music, plus their parents. */
    private void addFolder() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        boolean was = prefs.blacklistOn();
        for (Library.Group g : library.folders()) {
            String f = g.key;
            while (f != null && f.length() > 1 && !f.equals("/storage") && !f.equals("/storage/emulated")) {
                set.add(f);
                int slash = f.lastIndexOf('/');
                f = slash > 0 ? f.substring(0, slash) : null;
            }
        }
        String[] items = set.toArray(new String[0]);
        if (items.length == 0) { Ui.toast(this, was ? "No visible folders" : "No folders"); return; }
        Ui.choose(this, "Blacklist folder (and subfolders)", items, -1, w -> {
            db.addBlacklist(Db.BL_FOLDER, items[w], items[w]);
            library.refilter();
            renderBlacklist();
        });
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
}
