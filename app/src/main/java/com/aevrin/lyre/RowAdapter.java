/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** One adapter for every list: songs, groups, section headers and action rows. */
final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int SONG = 0, GROUP = 1, HEADER = 2, ACTION = 3, BAR = 4;

    interface Anchored { void run(View anchor); }

    static final class Row {
        int type;
        String t1 = "", t2 = "", t3 = "";
        Song song;          // art source for SONG and GROUP rows
        int icon = R.drawable.ic_note;
        boolean current;    // highlighted (e.g. playing now)
        View.OnClickListener click;
        View.OnClickListener shuffle;   // BAR rows: the shuffle button (click = play)
        Anchored menu;
        Object tag;

        static Row header(String title) {
            Row r = new Row();
            r.type = HEADER;
            r.t1 = title;
            return r;
        }

        /** "N songs" with shuffle, play and ⋮ buttons. */
        static Row bar(String title, View.OnClickListener play, View.OnClickListener shuffle, Anchored menu) {
            Row r = new Row();
            r.type = BAR;
            r.t1 = title;
            r.click = play;
            r.shuffle = shuffle;
            r.menu = menu;
            return r;
        }

        static Row action(int icon, String title, View.OnClickListener click) {
            Row r = new Row();
            r.type = ACTION;
            r.icon = icon;
            r.t1 = title;
            r.click = click;
            return r;
        }
    }

    interface DragStarter { void startDrag(RecyclerView.ViewHolder h); }

    private final List<Row> rows = new ArrayList<>();
    private DragStarter dragStarter;

    List<Row> rows() { return rows; }

    @SuppressLint("NotifyDataSetChanged")
    void set(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    /** Shows drag handles on song rows; null hides them. */
    void setDragStarter(DragStarter d) { dragStarter = d; }

    void move(int from, int to) {
        Row r = rows.remove(from);
        rows.add(to, r);
        notifyItemMoved(from, to);
    }

    void remove(int pos) {
        rows.remove(pos);
        notifyItemRemoved(pos);
    }

    @Override public int getItemCount() { return rows.size(); }
    @Override public int getItemViewType(int position) { return rows.get(position).type; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        if (viewType == HEADER) {
            TextView t = Ui.text(c, "", 13, R.color.accent, true);
            t.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 4));
            t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(t) { };
        }
        if (viewType == BAR) return new BarHolder(c);
        return new ItemHolder(c, viewType);
    }

    /** Header bar over a song list: count, shuffle all, play all, more. */
    static final class BarHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final ImageButton shuffle, play, more;

        BarHolder(Context c) {
            super(new LinearLayout(c));
            LinearLayout row = (LinearLayout) itemView;
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            title = Ui.text(c, "", 14, R.color.text_secondary, true);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            shuffle = Ui.icon(c, R.drawable.ic_shuffle, "Shuffle all", null);
            play = Ui.icon(c, R.drawable.ic_play, "Play all", null);
            more = Ui.icon(c, R.drawable.ic_more, "More for all songs", null);
            row.addView(shuffle);
            row.addView(play);
            row.addView(more);
        }

        void bind(Row r) {
            title.setText(r.t1);
            play.setOnClickListener(r.click);
            shuffle.setOnClickListener(r.shuffle);
            more.setOnClickListener(v -> { if (r.menu != null) r.menu.run(v); });
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (r.type == HEADER) {
            ((TextView) holder.itemView).setText(r.t1);
            return;
        }
        if (r.type == BAR) { ((BarHolder) holder).bind(r); return; }
        ((ItemHolder) holder).bind(r, dragStarter);
    }

    final class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView handle, art;
        final TextView t1, t2, t3;
        final ImageButton more;
        final int artPx;

        @SuppressLint("ClickableViewAccessibility")
        ItemHolder(Context c, int type) {
            super(new LinearLayout(c));
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(c, type == ACTION ? 52 : 64));
            row.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
            row.setBackground(Ui.ripple(c, false));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            handle = new ImageView(c);
            handle.setImageResource(R.drawable.ic_drag);
            handle.setAlpha(0.6f);
            handle.setPadding(0, 0, Ui.dp(c, 8), 0);
            row.addView(handle, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 48)));
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && dragStarter != null) dragStarter.startDrag(this);
                return false;
            });

            artPx = Ui.dp(c, type == ACTION ? 28 : 48);
            art = new ImageView(c);
            art.setClipToOutline(true);
            art.setBackground(Ui.rounded(c, R.color.surface, 6));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(artPx, artPx);
            ap.rightMargin = Ui.dp(c, 12);
            row.addView(art, ap);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            t1 = Ui.text(c, "", 15, type == ACTION ? R.color.accent : R.color.text_primary, false);
            t2 = Ui.text(c, "", 13, R.color.text_muted, false);
            texts.addView(t1);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            t3 = Ui.text(c, "", 12, R.color.text_muted, false);
            t3.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 4), 0);
            row.addView(t3, Ui.wrap());

            more = Ui.icon(c, R.drawable.ic_more, "More", null);
            more.setAlpha(0.7f);
            row.addView(more);
        }

        void bind(Row r, DragStarter drag) {
            Context c = itemView.getContext();
            handle.setVisibility(drag != null && r.type == SONG ? View.VISIBLE : View.GONE);
            t1.setText(r.t1);
            t1.setTextColor(Ui.color(c, r.current || r.type == ACTION ? R.color.accent : R.color.text_primary));
            t2.setText(r.t2);
            t2.setVisibility(r.t2 == null || r.t2.isEmpty() ? View.GONE : View.VISIBLE);
            t3.setText(r.t3);
            t3.setVisibility(r.t3 == null || r.t3.isEmpty() ? View.GONE : View.VISIBLE);
            if (r.type == ACTION) {
                art.setBackground(null);
                art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                art.setImageResource(r.icon);
                art.setTag(R.id.art_key, null);
            } else {
                art.setBackground(Ui.rounded(c, R.color.surface, 6));
                Art.into(art, r.song, Math.max(artPx, 96), r.icon);
            }
            itemView.setOnClickListener(r.click);
            if (r.menu != null) {
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(v -> r.menu.run(v));
                itemView.setOnLongClickListener(v -> { r.menu.run(more); return true; });
            } else {
                more.setVisibility(View.GONE);
                itemView.setOnLongClickListener(null);
            }
        }
    }
}
