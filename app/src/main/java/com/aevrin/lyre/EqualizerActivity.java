/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Ten-band equalizer with presets. Changes are heard immediately. */
public class EqualizerActivity extends Activity {

    private static final int STEPS_PER_DB = 2;   // 0.5 dB steps
    private int themeVersion;
    private Prefs prefs;
    private Remote remote;
    private float[] gains;
    private final SeekBar[] bars = new SeekBar[Eq.BANDS];
    private final TextView[] values = new TextView[Eq.BANDS];
    private TextView presetValue;
    private Switch onSwitch;
    private LinearLayout bandsBox;
    private boolean binding;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        prefs = LyreApp.get().prefs();
        gains = prefs.eqGains();
        remote = new Remote(this, null);
        setContentView(build());
        bind();
    }

    @Override protected void onStart() { super.onStart(); remote.connect(c -> push()); }
    @Override protected void onStop() { super.onStop(); remote.release(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (themeVersion != Theme.version) recreate();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);
        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 16), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        top.addView(Ui.text(this, "Equalizer", 20, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        onSwitch = new Switch(this);
        onSwitch.setOnCheckedChangeListener((b, v) -> {
            if (binding) return;
            prefs.eqOn(v);
            bandsBox.setAlpha(v ? 1f : 0.5f);
            push();
        });
        top.addView(onSwitch);
        root.addView(top, Ui.matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView pl = Ui.text(this, "Preset", 13, R.color.accent, true);
        pl.setPadding(0, Ui.dp(this, 8), 0, 0);
        form.addView(pl);
        presetValue = Ui.text(this, "", 16, R.color.text_primary, false);
        presetValue.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        presetValue.setBackground(Ui.ripple(this, false));
        presetValue.setOnClickListener(v -> Ui.choose(this, "Preset", Eq.PRESET_NAMES, Eq.match(gains), w -> {
            gains = Eq.preset(w);
            if (!prefs.eqOn()) prefs.eqOn(true);
            save();
            bind();
        }));
        form.addView(presetValue, Ui.matchWrap());

        bandsBox = new LinearLayout(this);
        bandsBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Eq.BANDS; i++) {
            final int band = i;
            LinearLayout row = new LinearLayout(this);
            Ui.center(row);
            row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            TextView f = Ui.text(this, Eq.label(Eq.CENTER[i]) + " Hz", 13, R.color.text_secondary, false);
            row.addView(f, new LinearLayout.LayoutParams(Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar sb = new SeekBar(this);
            sb.setMax((int) (Eq.MAX_DB * 2 * STEPS_PER_DB));
            sb.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
            sb.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (binding || !user) return;
                    gains[band] = p / (float) STEPS_PER_DB - Eq.MAX_DB;
                    values[band].setText(db(gains[band]));
                    presetValue.setText(name());
                    save();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = sb;
            row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = Ui.text(this, "", 13, R.color.text_secondary, false);
            v.setGravity(android.view.Gravity.END);
            values[i] = v;
            row.addView(v, new LinearLayout.LayoutParams(Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
            bandsBox.addView(row, Ui.matchWrap());
        }
        form.addView(bandsBox, Ui.matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);
        buttons.addView(Ui.button(this, "Reset to flat", v -> { gains = Eq.preset(0); save(); bind(); }));
        form.addView(buttons);

        PlaybackService svc = PlaybackService.get();
        TextView note = Ui.text(this, (svc != null && !svc.canBoost()
                ? "This device doesn't offer the audio effect Lyre uses, so the equalizer can't work here. "
                : "") + "Boosted bands lower the overall level a little so loud passages don't distort. "
                + "Works together with volume normalization.", 12, R.color.text_muted, false);
        note.setSingleLine(false);
        note.setPadding(0, Ui.dp(this, 16), 0, 0);
        form.addView(note);
        return root;
    }

    private void bind() {
        binding = true;
        onSwitch.setChecked(prefs.eqOn());
        bandsBox.setAlpha(prefs.eqOn() ? 1f : 0.5f);
        for (int i = 0; i < Eq.BANDS; i++) {
            bars[i].setProgress(Math.round((gains[i] + Eq.MAX_DB) * STEPS_PER_DB));
            values[i].setText(db(gains[i]));
        }
        presetValue.setText(name());
        binding = false;
    }

    private String name() {
        int m = Eq.match(gains);
        return (m >= 0 ? Eq.PRESET_NAMES[m] : "Custom") + " ▾";
    }

    private void save() {
        prefs.eqGains(gains);
        push();
    }

    private void push() {
        PlaybackService svc = PlaybackService.get();
        if (svc != null) svc.applyEq();
    }

    private static String db(float g) {
        return String.format(Locale.ROOT, "%+.1f dB", g);
    }
}
