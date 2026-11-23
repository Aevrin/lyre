/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A draggable scrollbar on the right edge of a long list. Dragging the thumb
 * jumps through the list and shows a bubble with the first letter of the row
 * under it. Hidden for short lists.
 */
final class FastScroller extends View {

    interface Labeler { String label(int position); }

    private final RecyclerView list;
    private final LinearLayoutManager lm;
    private Labeler labeler;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG),
            bubble = new Paint(Paint.ANTI_ALIAS_FLAG), letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean dragging;
    private float thumbFrac;   // 0..1 position of the thumb
    private String bubbleText = "";
    private final int touchWidth, barWidth, thumbHeight, bubbleSize;

    FastScroller(Context c, RecyclerView list, LinearLayoutManager lm) {
        super(c);
        this.list = list;
        this.lm = lm;
        touchWidth = Ui.dp(c, 28);
        barWidth = Ui.dp(c, 4);
        thumbHeight = Ui.dp(c, 44);
        bubbleSize = Ui.dp(c, 56);
        int accent = Ui.color(c, R.color.accent);
        track.setColor((Ui.color(c, R.color.text_muted) & 0x00FFFFFF) | 0x22000000);
        thumb.setColor(accent);
        bubble.setColor(accent);
        letter.setColor(0xFFFFFFFF);
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setTextSize(Ui.dp(c, 26));
        letter.setFakeBoldText(true);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!dragging) sync();
            }
        });
    }

    void setLabeler(Labeler l) { labeler = l; }

    /** Width to give this view: room for the touch area plus the bubble to its left. */
    int preferredWidth() { return touchWidth + bubbleSize + Ui.dp(getContext(), 8); }

    private boolean active() {
        RecyclerView.Adapter<?> a = list.getAdapter();
        return a != null && a.getItemCount() > 40 && list.computeVerticalScrollRange() > list.getHeight() * 2;
    }

    void sync() {
        int range = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent();
        thumbFrac = range <= 0 ? 0 : Math.min(1f, list.computeVerticalScrollOffset() / (float) range);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!active()) return;
        float x = getWidth() - touchWidth / 2f;
        float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
        float w = dragging ? barWidth * 1.6f : barWidth;
        r.set(x - w / 2, top, x + w / 2, bottom);
        canvas.drawRoundRect(r, w, w, track);
        float ty = top + (bottom - top - thumbHeight) * thumbFrac;
        r.set(x - w, ty, x + w, ty + thumbHeight);
        canvas.drawRoundRect(r, w, w, thumb);
        if (dragging && !bubbleText.isEmpty()) {
            float cx = getWidth() - touchWidth - bubbleSize / 2f;
            float cy = Math.max(top + bubbleSize / 2f, Math.min(bottom - bubbleSize / 2f, ty + thumbHeight / 2f));
            canvas.drawCircle(cx, cy, bubbleSize / 2f, bubble);
            canvas.drawText(bubbleText, cx, cy - (letter.descent() + letter.ascent()) / 2, letter);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!active()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getX() < getWidth() - touchWidth) return false;   // let touches pass to the list
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                scrollTo(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) scrollTo(e.getY());
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return dragging;
        }
    }

    private void scrollTo(float y) {
        float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
        thumbFrac = Math.max(0f, Math.min(1f, (y - top - thumbHeight / 2f) / (bottom - top - thumbHeight)));
        RecyclerView.Adapter<?> a = list.getAdapter();
        int n = a == null ? 0 : a.getItemCount();
        if (n == 0) return;
        int pos = Math.min(n - 1, Math.round(thumbFrac * (n - 1)));
        lm.scrollToPositionWithOffset(pos, 0);
        bubbleText = labeler == null ? "" : labeler.label(pos);
        invalidate();
    }
}
