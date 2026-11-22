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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans for duplicate songs and lets you remove them: all at once (keeping the
 * best copy of each) or by hand, with every file's full location shown.
 * Removed files go to Android's trash, where they can be restored for 30 days.
 */
public class DuplicatesActivity extends Activity implements Actions.Host {

    private int themeVersion;

    private static final int REQ_TRASH = 1;
    private static final int MODE_SIMILAR = 0, MODE_IDENTICAL = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Remote remote;

    private int mode = MODE_SIMILAR;
    private boolean scanning;
    private List<List<Song>> groups = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private List<Song> pendingTrash = Collections.emptyList();

    private TextView modeValue, status;
    private ProgressBar progress;
    private Button scanButton, removeButton;
    private LinearLayout actions;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        remote = new Remote(this, null);
        setContentView(build());
        scan();
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
    @Override public void openGroup(int kind, String key) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_KIND, kind).putExtra(MainActivity.EXTRA_KEY, key));
    }
    @Override public void refresh() { scan(); }

    // ---------------------------------------------------------------- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        top.addView(Ui.text(this, "Duplicates", 20, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        top.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        root.addView(top, Ui.matchWrap());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        LinearLayout modeRow = new LinearLayout(this);
        Ui.center(modeRow);
        modeValue = Ui.text(this, "", 15, R.color.accent, true);
        modeValue.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        modeValue.setBackground(Ui.ripple(this, false));
        modeValue.setOnClickListener(v -> Ui.choose(this, "Find",
                new String[]{"Same song (title, artist and length)", "Identical files (exact copies)"}, mode, w -> {
                    mode = w;
                    scan();
                }));
        modeRow.addView(modeValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanButton = Ui.button(this, "Scan", v -> { if (scanning) cancel.set(true); else scan(); });
        modeRow.addView(scanButton);
        bar.addView(modeRow, Ui.matchWrap());
        status = Ui.text(this, "", 13, R.color.text_muted, false);
        status.setSingleLine(false);
        bar.addView(status, Ui.matchWrap());

        actions = new LinearLayout(this);
        actions.setPadding(0, Ui.dp(this, 8), 0, 0);
        actions.addView(Ui.button(this, "Select extras", v -> selectExtras()));
        actions.addView(gap());
        actions.addView(Ui.button(this, "Clear", v -> { selected.clear(); refreshAll(); }));
        actions.addView(gap());
        removeButton = Ui.button(this, "Remove", v -> confirmRemove());
        actions.addView(removeButton);
        bar.addView(actions, Ui.matchWrap());
        bar.setBackgroundColor(Ui.color(this, R.color.bg_secondary));
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

    private void scan() {
        if (scanning) return;
        scanning = true;
        cancel.set(false);
        selected.clear();
        groups = new ArrayList<>();
        refreshAll();
        progress.setVisibility(View.VISIBLE);
        scanButton.setText("Stop");
        final int m = mode;
        final List<Song> songs = new ArrayList<>(LyreApp.get().library().songs());
        status.setText("Scanning " + Library.plural(songs.size(), "song") + "…");
        io.execute(() -> {
            List<List<Song>> found;
            if (m == MODE_SIMILAR) {
                found = Dupes.similar(songs);
            } else {
                found = Dupes.identical(songs, s -> getContentResolver().openInputStream(s.uri()),
                        new Dupes.Progress() {
                            @Override public void at(int done, int total) {
                                if (done % 10 == 0) main.post(() -> status.setText(
                                        "Comparing files with the same size: " + done + " / " + total));
                            }
                            @Override public boolean cancelled() { return cancel.get(); }
                        });
            }
            found.sort((a, b) -> Dupes.norm(a.get(0).title).compareTo(Dupes.norm(b.get(0).title)));
            main.post(() -> {
                if (isDestroyed()) return;
                scanning = false;
                groups = found;
                progress.setVisibility(View.GONE);
                scanButton.setText("Scan again");
                refreshAll();
            });
        });
    }

    private void refreshAll() {
        modeValue.setText((mode == MODE_SIMILAR ? "Same song" : "Identical files") + " ▾");
        int extras = 0;
        long extraBytes = 0, selBytes = 0;
        for (List<Song> g : groups) {
            extras += g.size() - 1;
            for (int i = 1; i < g.size(); i++) extraBytes += g.get(i).size;
            for (Song s : g) if (selected.contains(s.id)) selBytes += s.size;
        }
        if (!scanning) {
            status.setText(groups.isEmpty() ? "No duplicates found."
                    : Library.plural(groups.size(), "song") + " with copies · " + Library.plural(extras, "extra file")
                    + " (" + mb(extraBytes) + "). The suggested copy to keep is listed first: lossless, then highest bitrate.");
        }
        actions.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        removeButton.setText(selected.isEmpty() ? "Remove" : "Remove " + selected.size() + " (" + mb(selBytes) + ")");
        removeButton.setEnabled(!selected.isEmpty());
        removeButton.setAlpha(selected.isEmpty() ? 0.5f : 1f);
        adapter.rebuild();
    }

    private static String mb(long b) { return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0); }

    /** Selects every copy except the suggested one in each group. */
    private void selectExtras() {
        selected.clear();
        for (List<Song> g : groups) for (int i = 1; i < g.size(); i++) selected.add(g.get(i).id);
        refreshAll();
    }

    /** Keeps this copy: selects all the others in its group. */
    private void keepOnly(List<Song> g, Song keep) {
        for (Song s : g) {
            if (s.id == keep.id) selected.remove(s.id); else selected.add(s.id);
        }
        refreshAll();
    }

    // ---------------------------------------------------------------- removing

    private void confirmRemove() {
        if (selected.isEmpty()) return;
        List<Song> trash = new ArrayList<>();
        int whole = 0;
        for (List<Song> g : groups) {
            int sel = 0;
            for (Song s : g) if (selected.contains(s.id)) { trash.add(s); sel++; }
            if (sel == g.size()) whole++;
        }
        String msg = Library.plural(trash.size(), "file") + " will go to Android's trash. You can restore them "
                + "from the trash in the Files app for 30 days.\n\nPlaylists that contain a removed copy will "
                + "point to the copy you kept instead.";
        if (whole > 0) msg += "\n\nWarning: every copy of " + Library.plural(whole, "song")
                + " is selected, so those songs will be gone completely.";
        Ui.confirm(this, "Remove duplicates?", msg, "Remove", () -> trash(trash));
    }

    private void trash(List<Song> songs) {
        pendingTrash = songs;
        List<Uri> uris = new ArrayList<>();
        for (Song s : songs) uris.add(s.uri());
        try {
            PendingIntent pi = MediaStore.createTrashRequest(getContentResolver(), uris, true);
            startIntentSenderForResult(pi.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Ui.toast(this, "Couldn't ask to remove files: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TRASH) return;
        if (res != RESULT_OK) { Ui.toast(this, "Nothing removed"); return; }
        Db db = LyreApp.get().db();
        Set<Long> gone = new HashSet<>();
        for (Song s : pendingTrash) gone.add(s.id);
        // Point playlists (and play counts) at the surviving copy of each song.
        List<List<Song>> left = new ArrayList<>();
        for (List<Song> g : groups) {
            Song keep = null;
            for (Song s : g) if (!gone.contains(s.id)) { keep = s; break; }
            List<Song> rest = new ArrayList<>();
            for (Song s : g) {
                if (gone.contains(s.id)) { if (keep != null) db.replacePath(s.path, keep.path); }
                else rest.add(s);
            }
            if (rest.size() > 1) left.add(rest);
        }
        Ui.toast(this, "Moved " + Library.plural(pendingTrash.size(), "file") + " to the trash");
        pendingTrash = Collections.emptyList();
        groups = left;
        selected.clear();
        refreshAll();
        LyreApp.get().library().reload();
    }

    // ---------------------------------------------------------------- list

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        // Each entry is a header (List<Song> group) or a song row (Object[]{group, song, isBest}).
        private final List<Object> items = new ArrayList<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void rebuild() {
            items.clear();
            for (List<Song> g : groups) {
                items.add(g);
                for (int i = 0; i < g.size(); i++) items.add(new Object[]{g, g.get(i), i == 0});
            }
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int p) { return items.get(p) instanceof List ? 0 : 1; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 0) {
                TextView t = Ui.text(DuplicatesActivity.this, "", 14, R.color.accent, true);
                t.setPadding(Ui.dp(DuplicatesActivity.this, 16), Ui.dp(DuplicatesActivity.this, 16),
                        Ui.dp(DuplicatesActivity.this, 16), Ui.dp(DuplicatesActivity.this, 4));
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(t) { };
            }
            return new Row();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            Object it = items.get(p);
            if (it instanceof List) {
                List<Song> g = (List<Song>) it;
                ((TextView) h.itemView).setText(g.get(0).title + " — " + g.get(0).artist + " · " + g.size() + " copies");
                return;
            }
            Object[] a = (Object[]) it;
            ((Row) h).bind((List<Song>) a[0], (Song) a[1], (Boolean) a[2]);
        }
    }

    private final class Row extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView line1, line2, path, badge;
        final ImageButton more;

        Row() {
            super(new LinearLayout(DuplicatesActivity.this));
            Activity c = DuplicatesActivity.this;
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
            LinearLayout l1 = new LinearLayout(c);
            Ui.center(l1);
            line1 = Ui.text(c, "", 15, R.color.text_primary, false);
            l1.addView(line1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            badge = Ui.text(c, "Keep", 11, R.color.accent, true);
            badge.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
            l1.addView(badge);
            texts.addView(l1, Ui.matchWrap());
            line2 = Ui.text(c, "", 13, R.color.text_secondary, false);
            texts.addView(line2);
            path = Ui.text(c, "", 12, R.color.text_muted, false);
            path.setSingleLine(false);
            path.setTextIsSelectable(false);
            texts.addView(path);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            more = Ui.icon(c, R.drawable.ic_more, "More", null);
            row.addView(more);
        }

        void bind(List<Song> g, Song s, boolean best) {
            check.setChecked(selected.contains(s.id));
            line1.setText(s.album);
            badge.setVisibility(best ? View.VISIBLE : View.GONE);
            line2.setText(Dupes.quality(s));
            path.setText(s.path);
            itemView.setOnClickListener(v -> {
                if (!selected.remove(s.id)) selected.add(s.id);
                refreshAll();
            });
            more.setOnClickListener(v -> {
                PopupMenu m = new PopupMenu(DuplicatesActivity.this, v);
                m.getMenu().add(0, 1, 0, "Keep this one, remove the others");
                m.getMenu().add(0, 2, 0, "Play");
                m.getMenu().add(0, 3, 0, "Details");
                m.getMenu().add(0, 4, 0, "Hide with the blacklist instead");
                m.setOnMenuItemClickListener(it -> {
                    switch (it.getItemId()) {
                        case 1: keepOnly(g, s); break;
                        case 2: remote.play(Collections.singletonList(s), 0, false); break;
                        case 3: Actions.details(DuplicatesActivity.this, s); break;
                        case 4: Actions.blacklist(DuplicatesActivity.this, Db.BL_SONG, s.path, s.title + " — " + s.artist); break;
                        default: return false;
                    }
                    return true;
                });
                m.show();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Theme or accent changed in Settings while this screen was open.
        if (themeVersion != Theme.version) recreate();
    }
}
