/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Small view and dialog helpers so screens can be built in code. */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        Integer o = Theme.override(c, res);
        return o != null ? o : c.getColor(res);
    }

    /** Pads a root view for the status and navigation bars (Android 15+ is always edge-to-edge). */
    static void fitSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(b.left, b.top, b.right, Math.max(b.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    static TextView text(Context c, String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * LyreApp.get().prefs().textScale());
        t.setTextColor(color(c, colorRes));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static ImageButton icon(Context c, int drawable, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(drawable);
        b.setContentDescription(desc);
        b.setBackground(ripple(c, true));
        int p = dp(c, 10);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        if (desc != null) b.setTooltipText(desc);
        return b;
    }

    static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(c, R.color.button));
        bg.setCornerRadius(dp(c, 8));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setOnClickListener(l);
        return b;
    }

    static RippleDrawable ripple(Context c, boolean borderless) {
        int rc = (color(c, R.color.accent) & 0x00FFFFFF) | 0x33000000;
        if (borderless) return new RippleDrawable(ColorStateList.valueOf(rc), null, null);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(rc), null, mask);
    }

    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /** One-line text input dialog. */
    static void input(Activity a, String title, String initial, String okLabel, Consumer<String> ok) {
        EditText e = new EditText(a);
        e.setText(initial);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(a);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        box.addView(e);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(okLabel, (di, w) -> {
                    String v = e.getText().toString().trim();
                    if (!v.isEmpty()) ok.accept(v);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            e.requestFocus();
            if (d.getWindow() != null) d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        });
        d.show();
    }

    /** Single-choice list; checked = -1 for a plain list. */
    static void choose(Activity a, String title, String[] items, int checked, IntConsumer pick) {
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title);
        if (checked >= 0) {
            b.setSingleChoiceItems(items, checked, (d, w) -> { d.dismiss(); pick.accept(w); });
        } else {
            b.setItems(items, (d, w) -> pick.accept(w));
        }
        b.setNegativeButton("Cancel", null).show();
    }

    static void confirm(Activity a, String title, String msg, String okLabel, Runnable ok) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(okLabel, (d, w) -> ok.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    static GradientDrawable rounded(Context c, int colorRes, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(c, colorRes));
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static void center(LinearLayout l) { l.setGravity(Gravity.CENTER_VERTICAL); }
}
