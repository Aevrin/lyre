/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * "Volume for this song": an adjustment in dB on top of automatic normalization,
 * for songs that measure loud (one loud moment) but sound quiet. Saved in Lyre, not
 * in the file, and kept through tag edits. Live while the song is playing.
 */
final class VolumeDialog {
    private VolumeDialog() { }

    private static final float MAX = 12f;
    private static final int STEPS = 2;   // 0.5 dB

    static void show(Activity a, List<Song> songs, Runnable done) {
        if (songs.isEmpty()) return;
        Db db = LyreApp.get().db();
        Prefs prefs = LyreApp.get().prefs();
        Song first = songs.get(0);
        boolean single = songs.size() == 1;

        // Start from the shared value, or 0 if the songs differ.
        float start = db.volume(first.path);
        boolean mixed = false;
        for (Song s : songs) if (Math.abs(db.volume(s.path) - start) > 0.01f) { mixed = true; break; }
        if (mixed) start = 0f;
        final float initial = start;

        PlaybackService svc = PlaybackService.get();
        Song playing = svc == null ? null : svc.current();
        final boolean live = single && playing != null && playing.id == first.id;
        final Db.Gain record = db.gain(first);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);

        TextView measured = Ui.text(a, "", 14, R.color.text_secondary, false);
        measured.setSingleLine(false);
        box.addView(measured);
        TextView value = Ui.text(a, "", 22, R.color.accent, true);
        value.setPadding(0, Ui.dp(a, 12), 0, 0);
        box.addView(value);
        SeekBar bar = new SeekBar(a);
        bar.setMax((int) (MAX * 2 * STEPS));
        bar.setProgress(Math.round((initial + MAX) * STEPS));
        bar.setProgressTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent)));
        box.addView(bar, Ui.matchWrap());
        TextView result = Ui.text(a, "", 13, R.color.text_muted, false);
        result.setSingleLine(false);
        box.addView(result);
        TextView note = Ui.text(a, (live ? "You'll hear changes right away. " : "")
                + "Saved in Lyre, not in the file, and kept if you edit tags."
                + (mixed ? " These songs have different settings now; saving sets them all to this value." : ""),
                12, R.color.text_muted, false);
        note.setSingleLine(false);
        note.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(note);

        if (single) {
            String m = record != null && !Float.isNaN(record.lufs)
                    ? String.format(Locale.ROOT, "Measured loudness: %.1f LUFS", record.lufs)
                    : record != null && !Float.isNaN(record.rgTrack)
                    ? String.format(Locale.ROOT, "ReplayGain tag: %+.2f dB (not measured yet)", record.rgTrack)
                    : "Not measured yet (it's measured when it plays).";
            Normalizer.Plan auto = Normalizer.plan(prefs, record, 0f);
            measured.setText(m + String.format(Locale.ROOT, "\nAutomatic: plays at %+.1f dB", auto.total)
                    + (prefs.normMode() == Prefs.NORM_OFF ? " (normalization is off)" : ""));
        } else {
            measured.setText(Library.plural(songs.size(), "song") + ". The adjustment is added to each song's automatic volume.");
        }

        Runnable refresh = () -> {
            float v = bar.getProgress() / (float) STEPS - MAX;
            value.setText(Math.abs(v) < 0.01f ? "Automatic (0 dB)" : String.format(Locale.ROOT, "%+.1f dB", v));
            if (single) {
                Normalizer.Plan pl = Normalizer.plan(prefs, record, v);
                result.setText(String.format(Locale.ROOT, "Will play at %+.1f dB", pl.total)
                        + (pl.limited ? (pl.total > 0 ? " — that's the most Lyre can boost" : " — that's the most Lyre can cut") : ""));
            } else {
                result.setText("");
            }
            if (live && svc != null) svc.previewVolume(first, v);
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean user) { refresh.run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        refresh.run();

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(single ? "Volume for this song" : "Volume for " + Library.plural(songs.size(), "song"))
                .setView(box)
                .setPositiveButton("Save", (di, w) -> {
                    float v = bar.getProgress() / (float) STEPS - MAX;
                    for (Song s : songs) db.setVolume(s.path, v);
                    Ui.toast(a, Math.abs(v) < 0.01f ? "Volume set to automatic" : String.format(Locale.ROOT, "Volume %+.1f dB saved", v));
                })
                .setNeutralButton("Reset to automatic", (di, w) -> {
                    for (Song s : songs) db.setVolume(s.path, 0f);
                    Ui.toast(a, "Volume set to automatic");
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(x -> {
            PlaybackService sv = PlaybackService.get();
            if (sv != null) {
                if (live) sv.previewVolume(first, null);   // back to the saved value
                sv.reapplyGain();
            }
            if (done != null) done.run();
        });
        d.show();
    }

    /** "Automatic" or "custom +5.0 dB" for labels. */
    static String label(float v) {
        return Math.abs(v) < 0.01f ? "Automatic" : String.format(Locale.ROOT, "Custom %+.1f dB", v);
    }

}
