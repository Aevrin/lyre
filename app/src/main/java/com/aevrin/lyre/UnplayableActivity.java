/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lists files Lyre can't play: unsupported formats (WMA, APE, ...), empty files,
 * files that failed during playback and, with the full check, files Android has
 * no decoder for or can't read. Selected files go to Android's trash.
 */
public class UnplayableActivity extends Activity implements Actions.Host {

    private static final int REQ_TRASH = 1;

    private static final class Hit {
        final Song song;
        final String reason;
        Hit(Song song, String reason) { this.song = song; this.reason = reason; }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Remote remote;
    private List<Hit> hits = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private List<Song> pendingTrash = Collections.emptyList();
    private boolean scanning;

    private TextView status;
    private ProgressBar progress;
    private Button deepButton, removeButton;
    private LinearLayout actions;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        remote = new Remote(this, null);
        setContentView(build());
        scan(false);
    }

    @Override protected void onStart() { super.onStart(); remote.connect(null); }
    @Override protected void onStop() { super.onStop(); remote.release(); }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public Activity activity() { return this; }
    @Override public Remote remote() { return remote; }
    @Override public void openGroup(int kind, String key) { }
    @Override public void refresh() { }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        top.addView(Ui.text(this, "Unplayable files", 20, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        top.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        root.addView(top, Ui.matchWrap());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
        bar.setBackgroundColor(Ui.color(this, R.color.bg_secondary));
        status = Ui.text(this, "", 13, R.color.text_muted, false);
        status.setSingleLine(false);
        bar.addView(status, Ui.matchWrap());
        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(0, Ui.dp(this, 8), 0, 0);
        deepButton = Ui.button(this, "Check every file", v -> {
            if (scanning) cancel.set(true); else scan(true);
        });
        row1.addView(deepButton);
        bar.addView(row1);
        actions = new LinearLayout(this);
        actions.setPadding(0, Ui.dp(this, 8), 0, 0);
        actions.addView(Ui.button(this, "Select all", v -> {
            for (Hit h : hits) selected.add(h.song.id);
            refreshAll();
        }));
        actions.addView(gap());
        actions.addView(Ui.button(this, "Clear", v -> { selected.clear(); refreshAll(); }));
        actions.addView(gap());
        removeButton = Ui.button(this, "Remove", v -> confirmRemove());
        actions.addView(removeButton);
        bar.addView(actions, Ui.matchWrap());
        root.addView(bar, Ui.matchWrap());

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 16));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        return v;
    }

    // ---------------------------------------------------------------- scanning

    /** Quick pass always; deep also opens each remaining file and looks for a decoder. */
    private void scan(boolean deep) {
        if (scanning) return;
        scanning = true;
        cancel.set(false);
        progress.setVisibility(View.VISIBLE);
        deepButton.setText("Stop");
        final List<Song> songs = new ArrayList<>(LyreApp.get().library().all());
        final Map<String, String> failures = LyreApp.get().db().failures();
        io.execute(() -> {
            List<Hit> found = new ArrayList<>();
            List<Song> rest = new ArrayList<>();
            for (Song s : songs) {
                String why = Unplayable.quick(s, failures);
                if (why != null) found.add(new Hit(s, why)); else rest.add(s);
            }
            boolean finished = true;
            if (deep) {
                for (int i = 0; i < rest.size(); i++) {
                    if (cancel.get()) { finished = false; break; }
                    final int n = i;
                    if (i % 10 == 0) main.post(() -> status.setText("Checking " + n + " / " + rest.size() + "…"));
                    Song s = rest.get(i);
                    String why = Unplayable.deep(this, s);
                    if (why != null) found.add(new Hit(s, why));
                }
            }
            found.sort((a, b) -> a.song.path.compareToIgnoreCase(b.song.path));
            final boolean done = finished;
            main.post(() -> {
                if (isDestroyed()) return;
                scanning = false;
                hits = found;
                selected.clear();
                progress.setVisibility(View.GONE);
                deepButton.setText("Check every file");
                refreshAll();
                if (deep && !done) status.append("\nFull check stopped early.");
                else if (deep) status.append("\nFull check done.");
            });
        });
    }

    private void refreshAll() {
        long sel = 0;
        for (Hit h : hits) if (selected.contains(h.song.id)) sel += h.song.size;
        if (!scanning) {
            status.setText(hits.isEmpty()
                    ? "Nothing unplayable found. \"Check every file\" opens each file to look for damaged ones too (slower)."
                    : Library.plural(hits.size(), "file") + " Lyre can't play. Includes songs hidden by the blacklist. "
                    + "\"Check every file\" also finds damaged files (slower).");
        }
        actions.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
        removeButton.setText(selected.isEmpty() ? "Remove" : "Remove " + selected.size()
                + String.format(Locale.ROOT, " (%.1f MB)", sel / 1048576.0));
        removeButton.setEnabled(!selected.isEmpty());
        removeButton.setAlpha(selected.isEmpty() ? 0.5f : 1f);
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- removing

    private void confirmRemove() {
        List<Song> trash = new ArrayList<>();
        for (Hit h : hits) if (selected.contains(h.song.id)) trash.add(h.song);
        if (trash.isEmpty()) return;
        Ui.confirm(this, "Remove files?", Library.plural(trash.size(), "file")
                + " will go to Android's trash. You can restore them from the trash in the Files app for 30 days.",
                "Remove", () -> {
                    pendingTrash = trash;
                    List<Uri> uris = new ArrayList<>();
                    for (Song s : trash) uris.add(s.uri());
                    try {
                        PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                        startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | RuntimeException e) {
                        Ui.toast(this, "Couldn't ask to remove files: " + e.getMessage());
                    }
                });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TRASH) return;
        if (res != RESULT_OK) { Ui.toast(this, "Nothing removed"); return; }
        Db db = LyreApp.get().db();
        Set<Long> gone = new HashSet<>();
        for (Song s : pendingTrash) { gone.add(s.id); db.clearFailure(s.path); }
        List<Hit> left = new ArrayList<>();
        for (Hit h : hits) if (!gone.contains(h.song.id)) left.add(h);
        Ui.toast(this, "Moved " + Library.plural(pendingTrash.size(), "file") + " to the trash");
        pendingTrash = Collections.emptyList();
        hits = left;
        selected.clear();
        refreshAll();
        LyreApp.get().library().reload();
    }

    // ---------------------------------------------------------------- list

    private final class Adapter extends RecyclerView.Adapter<Row> {
        @Override public int getItemCount() { return hits.size(); }
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup p, int t) { return new Row(); }
        @Override public void onBindViewHolder(@NonNull Row h, int p) { h.bind(hits.get(p)); }
    }

    private final class Row extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView title, reason, path;
        final ImageButton more;

        Row() {
            super(new LinearLayout(UnplayableActivity.this));
            Activity c = UnplayableActivity.this;
            LinearLayout row = (LinearLayout) itemView;
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
            row.setBackground(Ui.ripple(c, false));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            check = new CheckBox(c);
            check.setClickable(false);
            row.addView(check);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(c, "", 15, R.color.text_primary, false);
            reason = Ui.text(c, "", 13, R.color.error, false);
            path = Ui.text(c, "", 12, R.color.text_muted, false);
            path.setSingleLine(false);
            texts.addView(title);
            texts.addView(reason);
            texts.addView(path);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            more = Ui.icon(c, R.drawable.ic_more, "More", null);
            row.addView(more);
        }

        void bind(Hit h) {
            Song s = h.song;
            check.setChecked(selected.contains(s.id));
            title.setText(s.title + " — " + s.artist);
            reason.setText(h.reason);
            path.setText(s.path + String.format(Locale.ROOT, " · %.1f MB", s.size / 1048576.0));
            itemView.setOnClickListener(v -> {
                if (!selected.remove(s.id)) selected.add(s.id);
                refreshAll();
            });
            more.setOnClickListener(v -> {
                PopupMenu m = new PopupMenu(UnplayableActivity.this, v);
                m.getMenu().add(0, 1, 0, "Try playing");
                m.getMenu().add(0, 2, 0, "Details");
                m.getMenu().add(0, 3, 0, "Hide with the blacklist instead");
                m.setOnMenuItemClickListener(it -> {
                    switch (it.getItemId()) {
                        case 1: remote.play(Collections.singletonList(s), 0, false); break;
                        case 2: Actions.details(UnplayableActivity.this, s); break;
                        case 3: Actions.blacklist(UnplayableActivity.this, Db.BL_SONG, s.path, s.title + " — " + s.artist); break;
                        default: return false;
                    }
                    return true;
                });
                m.show();
            });
        }
    }
}
