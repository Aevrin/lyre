/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The blacklist on its own screen, so a long list doesn't swamp Settings:
 * search, filter by type, remove one entry, or select several and remove them.
 */
public class BlacklistActivity extends Activity implements Library.Listener, Actions.Host {

    private int themeVersion;
    private static final String[] TYPES = {null, Db.BL_FOLDER, Db.BL_SONG, Db.BL_ALBUM, Db.BL_ARTIST, Db.BL_GENRE};
    private static final String[] TYPE_NAMES = {"All", "Folders", "Songs", "Albums", "Artists", "Genres"};

    private Db db;
    private Library library;
    private List<Db.BlEntry> all = new ArrayList<>();
    private final List<Db.BlEntry> shown = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private int filter;
    private EditText search;
    private LinearLayout chips, selectionBar;
    private TextView summary, selCount;
    private final Adapter adapter = new Adapter();
    private Remote remote;
    private java.util.Map<String, Integer> use = new java.util.HashMap<>();
    // Per entry: how many of its songs are in a playlist (computed on reload).
    private final java.util.Map<Long, Integer> entryUse = new java.util.HashMap<>();

    /** "1 folder", "200 songs". */
    static String count(int n, String type) {
        return Library.plural(n, type);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        db = LyreApp.get().db();
        library = LyreApp.get().library();
        remote = new Remote(this, null);
        setContentView(build());
        reload();
        library.addListener(this);
    }

    @Override protected void onStart() { super.onStart(); remote.connect(null); }
    @Override protected void onStop() { super.onStop(); remote.release(); }

    @Override public Activity activity() { return this; }
    @Override public Remote remote() { return remote; }
    @Override public void openGroup(int kind, String key) { }
    @Override public void refresh() { reload(); }

    @Override
    protected void onDestroy() {
        library.removeListener(this);
        super.onDestroy();
    }

    /** The hidden-song count is recomputed in the background after each change. */
    @Override
    public void onLibraryChanged() { refresh(); }

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
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        top.addView(Ui.text(this, "Blacklist", 20, R.color.text_primary, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.icon(this, R.drawable.ic_add, "Add folder", v -> addFolder()));
        root.addView(top, Ui.matchWrap());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        search = new EditText(this);
        search.setHint("Search the blacklist");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        bar.addView(search, Ui.matchWrap());
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        for (int i = 0; i < TYPES.length; i++) {
            final int idx = i;
            TextView c = Ui.text(this, TYPE_NAMES[i], 14, R.color.text_muted, true);
            c.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = Ui.dp(this, 6);
            c.setOnClickListener(v -> { filter = idx; applyFilter(); });
            chips.addView(c, lp);
        }
        hs.addView(chips);
        bar.addView(hs, Ui.matchWrap());
        summary = Ui.text(this, "", 13, R.color.text_muted, false);
        summary.setSingleLine(false);
        summary.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0);
        bar.addView(summary, Ui.matchWrap());

        selectionBar = new LinearLayout(this);
        Ui.center(selectionBar);
        selectionBar.setPadding(0, Ui.dp(this, 6), 0, 0);
        selCount = Ui.text(this, "", 14, R.color.accent, true);
        selectionBar.addView(selCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectionBar.addView(Ui.button(this, "Select shown", v -> {
            for (Db.BlEntry e : shown) selected.add(e.id);
            refresh();
        }));
        selectionBar.addView(gap());
        Button remove = Ui.button(this, "Remove", v -> removeSelected());
        selectionBar.addView(remove);
        bar.addView(selectionBar, Ui.matchWrap());
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

    private void reload() {
        all = db.blacklist();
        use = db.playlistUse();
        entryUse.clear();
        for (Db.BlEntry e : all) {
            if (Db.BL_SONG.equals(e.type)) {
                Integer n = use.get(e.value);
                entryUse.put(e.id, n == null ? 0 : n);
            } else {
                int n = 0;
                for (Song s : library.coveredBy(e)) if (use.containsKey(s.path)) n++;
                entryUse.put(e.id, n);
            }
        }
        selected.retainAll(ids(all));
        applyFilter();
    }

    private static Set<Long> ids(List<Db.BlEntry> l) {
        Set<Long> s = new HashSet<>();
        for (Db.BlEntry e : l) s.add(e.id);
        return s;
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String type = TYPES[filter];
        shown.clear();
        int[] counts = new int[TYPES.length];
        for (Db.BlEntry e : all) {
            counts[0]++;
            for (int i = 1; i < TYPES.length; i++) if (TYPES[i].equals(e.type)) counts[i]++;
            if (type != null && !type.equals(e.type)) continue;
            String label = (e.label == null ? e.value : e.label).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !label.contains(q) && !e.value.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView c = (TextView) chips.getChildAt(i);
            boolean on = i == filter;
            c.setText(TYPE_NAMES[i] + " " + counts[i]);
            c.setTextColor(Ui.color(this, on ? R.color.accent : R.color.text_muted));
            c.setBackground(on ? Ui.rounded(this, R.color.surface, 16) : null);
        }
        refresh();
    }

    private void refresh() {
        Prefs p = LyreApp.get().prefs();
        summary.setText(all.isEmpty() ? "Empty. Blacklist songs, albums, artists, genres or folders from their ⋮ menu, or add a folder with +."
                : (p.blacklistOn() ? "On, hiding " + Library.plural(library.hiddenCount(), "song")
                : "Off (switch it on in Settings)") + ". Songs you add to a playlist (or Favorites) still play there. "
                + "Tap entries to select them; ⋮ adds their songs to a playlist.");
        selCount.setText(selected.isEmpty() ? "" : selected.size() + " selected");
        selectionBar.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void removeSelected() {
        if (selected.isEmpty()) { Ui.toast(this, "Tap entries to select them first"); return; }
        int n = selected.size();
        Ui.confirm(this, "Remove " + Library.plural(n, "entry").replace("entrys", "entries") + "?",
                "They'll show in your library again.", "Remove", () -> {
                    for (long id : selected) db.removeBlacklist(id);
                    selected.clear();
                    library.refilter();
                    reload();
                });
    }

    /** ⋮ on an entry: add its songs to a playlist or Favorites, see them, or remove the entry. */
    private void entryMenu(View anchor, Db.BlEntry e) {
        boolean single = Db.BL_SONG.equals(e.type);
        List<Song> songs = library.coveredBy(e);
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        if (!single) m.getMenu().add(0, 1, 0, "Show songs (" + songs.size() + ")…");
        m.getMenu().add(0, 2, 0, single ? "Add to playlist…" : "Add all to playlist…");
        m.getMenu().add(0, 3, 0, single ? "Add to favorites" : "Add all to favorites");
        if (single && !songs.isEmpty()) m.getMenu().add(0, 4, 0, "Play");
        m.getMenu().add(0, 5, 0, "Remove from blacklist");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: showSongs(e, songs); break;
                case 2: if (songs.isEmpty()) noSongs(); else Actions.addToPlaylist(this, songs); break;
                case 3: if (songs.isEmpty()) noSongs(); else Actions.favoriteAll(this, songs); break;
                case 4: remote.play(songs, 0, false); break;
                case 5:
                    db.removeBlacklist(e.id);
                    selected.remove(e.id);
                    library.refilter();
                    reload();
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private void noSongs() { Ui.toast(this, "No songs in the library match this entry right now"); }

    /** Songs a folder/artist/album/genre entry hides; pick some to add to a playlist or Favorites. */
    private void showSongs(Db.BlEntry e, List<Song> songs) {
        if (songs.isEmpty()) { noSongs(); return; }
        String[] names = new String[songs.size()];
        boolean[] checked = new boolean[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            Integer n = use.get(s.path);
            names[i] = s.title + " — " + s.artist + (n != null && n > 0 ? "  (in " + Library.plural(n, "playlist") + ")" : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.label == null ? e.value : e.label)
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("Add to playlist…", (d, w) -> {
                    List<Song> pick = picked(songs, checked);
                    if (pick.isEmpty()) Ui.toast(this, "Tick some songs first"); else Actions.addToPlaylist(this, pick);
                })
                .setNeutralButton("Add to favorites", (d, w) -> {
                    List<Song> pick = picked(songs, checked);
                    if (pick.isEmpty()) Ui.toast(this, "Tick some songs first"); else Actions.favoriteAll(this, pick);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static List<Song> picked(List<Song> songs, boolean[] checked) {
        List<Song> out = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) if (checked[i]) out.add(songs.get(i));
        return out;
    }

    /** Pick from the folders that hold music, plus their parents. */
    private void addFolder() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (Song s : library.all()) {
            String f = s.folder;
            while (f != null && f.length() > 1 && !f.equals("/storage") && !f.equals("/storage/emulated")) {
                if (!set.add(f)) break;
                int slash = f.lastIndexOf('/');
                f = slash > 0 ? f.substring(0, slash) : null;
            }
        }
        String[] items = set.toArray(new String[0]);
        if (items.length == 0) { Ui.toast(this, "No folders"); return; }
        Ui.choose(this, "Blacklist folder (and subfolders)", items, -1, w -> {
            db.addBlacklist(Db.BL_FOLDER, items[w], items[w]);
            library.refilter();
            reload();
        });
    }

    private final class Adapter extends RecyclerView.Adapter<Row> {
        @Override public int getItemCount() { return shown.size(); }
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup p, int t) { return new Row(); }
        @Override public void onBindViewHolder(@NonNull Row h, int p) { h.bind(shown.get(p)); }
    }

    private final class Row extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView title, sub;

        Row() {
            super(new LinearLayout(BlacklistActivity.this));
            Activity c = BlacklistActivity.this;
            LinearLayout row = (LinearLayout) itemView;
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2));
            row.setBackground(Ui.ripple(c, false));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            check = new CheckBox(c);
            check.setClickable(false);
            row.addView(check);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = Ui.text(c, "", 15, R.color.text_primary, false);
            sub = Ui.text(c, "", 12, R.color.text_muted, false);
            texts.addView(title);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(Db.BlEntry e) {
            check.setChecked(selected.contains(e.id));
            title.setText(e.label == null ? e.value : e.label);
            String type = e.type.isEmpty() ? "" : e.type.substring(0, 1).toUpperCase(Locale.ROOT) + e.type.substring(1);
            Integer n = entryUse.get(e.id);
            String inPl = n == null || n == 0 ? ""
                    : Db.BL_SONG.equals(e.type) ? " · in " + Library.plural(n, "playlist")
                    : " · " + Library.plural(n, "song") + " in playlists";
            sub.setText((Db.BL_SONG.equals(e.type) ? type + " · " + e.value : type) + inPl);
            itemView.setOnClickListener(v -> {
                if (!selected.remove(e.id)) selected.add(e.id);
                refresh();
            });
            LinearLayout row = (LinearLayout) itemView;
            if (row.getChildCount() == 2) {
                row.addView(Ui.icon(BlacklistActivity.this, R.drawable.ic_more, "More", null));
            }
            row.getChildAt(2).setOnClickListener(v -> entryMenu(v, e));
        }
    }
}
