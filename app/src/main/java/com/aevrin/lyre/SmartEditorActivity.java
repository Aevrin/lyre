/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/**
 * Creates or edits a dynamic playlist: rules like "Date added more than 365 days
 * ago", "Play count at least 5" or "Length more than 10 minutes", matched all or
 * any, with a sort order and an optional limit. Shows how many songs match as you go.
 */
public class SmartEditorActivity extends Activity {

    private int themeVersion;

    static final String EXTRA_ID = "smart_id", EXTRA_PRESET = "smart_preset";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Smart smart;
    private long id = -1;
    private Map<String, Smart.Stat> stats;
    private EditText name, limit;
    private LinearLayout rulesBox;
    private TextView matchValue, sortValue, preview;
    private final Runnable recount = this::updatePreview;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        Db db = LyreApp.get().db();
        id = getIntent().getLongExtra(EXTRA_ID, -1);
        smart = id >= 0 ? db.smart(id) : Smart.preset(getIntent().getIntExtra(EXTRA_PRESET, 0));
        if (smart == null) { finish(); return; }
        stats = db.stats();
        setContentView(build());
        renderRules();
        updatePreview();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        top.addView(Ui.icon(this, R.drawable.ic_close, "Cancel", v -> finish()));
        top.addView(Ui.text(this, id >= 0 ? "Edit dynamic playlist" : "New dynamic playlist", 18, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.button(this, "Save", v -> save()));
        root.addView(top, Ui.matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        form.addView(label("Name"));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(smart.name);
        name.setHint("e.g. Long tracks I haven't heard in a while");
        form.addView(name, Ui.matchWrap());

        matchValue = picker(form, "Songs must match", () -> Ui.choose(this, "Songs must match",
                new String[]{"All rules", "Any rule"}, smart.matchAll ? 0 : 1, w -> {
                    smart.matchAll = w == 0;
                    refreshLabels();
                    updatePreview();
                }));

        form.addView(label("Rules"));
        rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(rulesBox, Ui.matchWrap());
        LinearLayout add = new LinearLayout(this);
        add.setPadding(0, Ui.dp(this, 8), 0, 0);
        add.addView(Ui.button(this, "Add rule", v -> {
            smart.rules.add(new Smart.Rule(Smart.ARTIST, Smart.CONTAINS, ""));
            renderRules();
        }));
        form.addView(add);

        sortValue = picker(form, "Order", () -> Ui.choose(this, "Order", Smart.SORT_NAMES, smart.sort, w -> {
            smart.sort = w;
            refreshLabels();
        }));

        form.addView(label("Limit (songs, empty for no limit)"));
        limit = new EditText(this);
        limit.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit.setText(smart.limit > 0 ? String.valueOf(smart.limit) : "");
        limit.addTextChangedListener(watcher());
        form.addView(limit, Ui.matchWrap());

        preview = Ui.text(this, "", 14, R.color.accent, true);
        preview.setPadding(0, Ui.dp(this, 20), 0, 0);
        form.addView(preview);
        TextView note = Ui.text(this, "Dynamic playlists update by themselves. A play counts after half a track "
                + "or 4 minutes; a skip is moving on with Next in the first 30 seconds. "
                + "\"More than … days ago\" includes songs never played.", 12, R.color.text_muted, false);
        note.setSingleLine(false);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        form.addView(note);
        refreshLabels();
        return root;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, R.color.accent, true);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
        return t;
    }

    private TextView picker(LinearLayout form, String title, Runnable onClick) {
        form.addView(label(title));
        TextView v = Ui.text(this, "", 16, R.color.text_primary, false);
        v.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        v.setBackground(Ui.ripple(this, false));
        v.setOnClickListener(x -> onClick.run());
        form.addView(v, Ui.matchWrap());
        return v;
    }

    private void refreshLabels() {
        matchValue.setText(smart.matchAll ? "All rules ▾" : "Any rule ▾");
        sortValue.setText(Smart.SORT_NAMES[Math.max(0, Math.min(smart.sort, Smart.SORT_NAMES.length - 1))] + " ▾");
    }

    private TextWatcher watcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(recount);
                main.postDelayed(recount, 300);
            }
        };
    }

    // ---------------------------------------------------------------- rules

    private void renderRules() {
        rulesBox.removeAllViews();
        for (int i = 0; i < smart.rules.size(); i++) rulesBox.addView(ruleRow(smart.rules.get(i)));
        if (smart.rules.isEmpty()) {
            TextView t = Ui.text(this, "No rules: every song matches.", 13, R.color.text_muted, false);
            rulesBox.addView(t);
        }
        updatePreview();
    }

    private View ruleRow(Smart.Rule r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.rounded(this, R.color.bg_secondary, 8));
        box.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, 8);
        box.setLayoutParams(lp);

        LinearLayout line = new LinearLayout(this);
        Ui.center(line);
        TextView field = chip(Smart.FIELD_NAMES[r.field]);
        TextView op = chip(Smart.opsFor(r.field)[Math.min(r.op, Smart.opsFor(r.field).length - 1)]);
        line.addView(field);
        line.addView(op);
        line.addView(Ui.spacer(this));
        line.addView(Ui.icon(this, R.drawable.ic_close, "Remove rule", v -> {
            smart.rules.remove(r);
            renderRules();
        }));
        box.addView(line, Ui.matchWrap());

        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setText(r.value);
        setValueInput(value, r);
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                r.value = s.toString();
                main.removeCallbacks(recount);
                main.postDelayed(recount, 300);
            }
        });
        box.addView(value, Ui.matchWrap());

        field.setOnClickListener(v -> Ui.choose(this, "Field", Smart.FIELD_NAMES, r.field, w -> {
            boolean kindChanged = Smart.isText(w) != Smart.isText(r.field) || Smart.isDate(w) != Smart.isDate(r.field)
                    || Smart.isFlag(w) != Smart.isFlag(r.field);
            r.field = w;
            if (kindChanged) { r.op = 0; r.value = ""; }
            renderRules();
        }));
        op.setOnClickListener(v -> Ui.choose(this, "Condition", Smart.opsFor(r.field), r.op, w -> {
            r.op = w;
            renderRules();
        }));
        return box;
    }

    private void setValueInput(EditText e, Smart.Rule r) {
        boolean need = Smart.needsValue(r.field, r.op);
        e.setVisibility(need ? View.VISIBLE : View.GONE);
        if (Smart.isText(r.field)) {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setHint("text");
        } else if (Smart.isDate(r.field)) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setHint("days (365 = a year)");
        } else if (r.field == Smart.LENGTH) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setHint("minutes");
        } else {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setHint(r.field == Smart.YEAR ? "year" : "number");
        }
    }

    private TextView chip(String s) {
        TextView t = Ui.text(this, s + " ▾", 14, R.color.text_primary, true);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(Ui.ripple(this, false));
        return t;
    }

    // ---------------------------------------------------------------- preview / save

    private void readForm() {
        smart.name = name.getText().toString().trim();
        String l = limit.getText().toString().trim();
        try { smart.limit = l.isEmpty() ? 0 : Math.max(0, Integer.parseInt(l)); }
        catch (NumberFormatException e) { smart.limit = 0; }
    }

    private void updatePreview() {
        if (preview == null) return;
        readForm();
        int n = smart.evaluate(LyreApp.get().library().songs(), stats, System.currentTimeMillis()).size();
        preview.setText(Library.plural(n, "song") + " match right now");
    }

    private void save() {
        readForm();
        if (smart.name.isEmpty()) smart.name = "Dynamic playlist";
        // Drop rules left without a value so they don't silently do nothing later.
        smart.rules.removeIf(r -> Smart.needsValue(r.field, r.op) && r.value.trim().isEmpty());
        LyreApp.get().db().saveSmart(id, smart);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Theme or accent changed in Settings while this screen was open.
        if (themeVersion != Theme.version) recreate();
    }
}
