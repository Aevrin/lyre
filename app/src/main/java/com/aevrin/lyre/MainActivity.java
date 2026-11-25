/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The library: tabs, drill-down pages, search and the mini player. */
public class MainActivity extends Activity implements Library.Listener, Actions.Host {

    private int themeVersion;

    static final String EXTRA_KIND = "open_kind", EXTRA_KEY = "open_key";
    private static final int REQ_PERM = 1, REQ_EXPORT = 2, REQ_IMPORT = 3, REQ_EXPORT_ALL = 4;

    // Page types: tabs, then detail pages (same numbers as Actions kinds), then search.
    private static final int T_SONGS = 10, T_ALBUMS = 11, T_ARTISTS = 12, T_GENRES = 13, T_FOLDERS = 14,
            T_PLAYLISTS = 15, SEARCH = 20;
    private static final int[] TABS = {T_SONGS, T_ALBUMS, T_ARTISTS, T_GENRES, T_FOLDERS, T_PLAYLISTS};
    static final String[] TAB_NAMES = {"Songs", "Albums", "Artists", "Genres", "Folders", "Playlists"};

    private static final class Page {
        final int type;
        final String key;
        Parcelable scroll;
        List<Song> cache;   // dynamic playlist contents, kept while the page is open
        Page(int type, String key) { this.type = type; this.key = key; }
    }

    private Library library;
    private Db db;
    private Prefs prefs;
    private Remote remote;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Page> stack = new ArrayList<>();
    private List<Song> pageSongs = new ArrayList<>();
    private Library.Group pageGroup;

    private RecyclerView list;
    private LinearLayoutManager lm;
    private RowAdapter adapter;
    private ItemTouchHelper dragger;
    private FastScroller fastScroller;
    private LinearLayout tabStrip, header, searchBar, permissionPanel;
    private TextView headerTitle, headerSub, emptyText;
    private EditText searchBox;
    private LinearLayout searchChips;
    private int searchFilter;   // 0 all, 1 songs, 2 artists, 3 albums
    private static final String[] FILTERS = {"All", "Songs", "Artists", "Albums"};
    private ImageButton sortButton;
    private View mini;
    private ImageView miniArt;
    private TextView miniTitle, miniArtist;
    private ImageButton miniPlay;
    private ProgressBar miniProgress;
    private long exportPlaylistId = -1, exportSmartId = -1;
    private boolean smartDirty;
    private boolean backRegistered;
    private final OnBackInvokedCallback back = this::goBack;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        LyreApp app = (LyreApp) getApplication();
        library = app.library();
        db = app.db();
        prefs = app.prefs();
        setContentView(build());
        remote = new Remote(this, new Player.Listener() {
            @Override public void onEvents(@NonNull Player p, @NonNull Player.Events e) { updateMini(); }
        });
        int tab = Math.max(0, Math.min(prefs.tab(), TABS.length - 1));
        if (!tabShown(tab)) tab = firstShownTab();
        stack.add(new Page(TABS[tab], null));
        library.addListener(this);
        if (library.hasPermission()) {
            if (!library.isLoaded()) library.reload();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQ_PERM);
        }
        render();
        handleOpen(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOpen(intent);
    }

    /** "Go to album/artist" from Now Playing. */
    private void handleOpen(Intent i) {
        if (i == null || !i.hasExtra(EXTRA_KIND)) return;
        int kind = i.getIntExtra(EXTRA_KIND, 0);
        String key = i.getStringExtra(EXTRA_KEY);
        i.removeExtra(EXTRA_KIND);
        if (key != null) openGroup(kind, key);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (library.hasPermission()) remote.connect(c -> updateMini());
        main.post(tick);
        if (smartDirty) {
            smartDirty = false;
            for (Page p : stack) p.cache = null;
        }
        render();
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
    public void onLibraryChanged() {
        for (Page p : stack) p.cache = null;
        render();
    }

    // ---------------------------------------------------------------- Actions.Host

    @Override public Activity activity() { return this; }
    @Override public Remote remote() { return remote; }
    @Override public void refresh() { render(); }

    @Override
    public void openGroup(int kind, String key) {
        closeSearch(false);
        push(new Page(kind, key));
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        // Top bar: the title reloads the library.
        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 4), 0);
        TextView title = Ui.text(this, getString(R.string.app_name), 22, R.color.text_primary, true);
        title.setOnClickListener(v -> {
            Art.forget();
            library.reload();
            Ui.toast(this, "Reloading library");
        });
        top.addView(title);
        top.addView(Ui.spacer(this));
        sortButton = Ui.icon(this, R.drawable.ic_sort, "Sort", v -> sortMenu());
        top.addView(sortButton);
        top.addView(Ui.icon(this, R.drawable.ic_search, "Search", v -> openSearch()));
        top.addView(Ui.icon(this, R.drawable.ic_settings, "Settings",
                v -> startActivity(new Intent(this, SettingsActivity.class))));
        root.addView(top, Ui.matchWrap());

        // Search bar (hidden until opened)
        searchBar = new LinearLayout(this);
        Ui.center(searchBar);
        searchBar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        searchBox = new EditText(this);
        searchBox.setHint("Search songs, albums, artists");
        searchBox.setSingleLine(true);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { if (searchBar.getVisibility() == View.VISIBLE) render(); }
        });
        searchBar.addView(searchBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addView(Ui.icon(this, R.drawable.ic_close, "Close search", v -> closeSearch(true)));
        searchBar.setVisibility(View.GONE);
        root.addView(searchBar, Ui.matchWrap());
        searchChips = new LinearLayout(this);
        searchChips.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            TextView c = Ui.text(this, FILTERS[i], 14, R.color.text_muted, true);
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = Ui.dp(this, 6);
            c.setOnClickListener(v -> { searchFilter = idx; render(); list.scrollToPosition(0); });
            searchChips.addView(c, lp);
        }
        searchChips.setVisibility(View.GONE);
        root.addView(searchChips, Ui.matchWrap());

        // Tabs
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, TAB_NAMES[i], 15, R.color.text_muted, true);
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            t.setBackground(Ui.ripple(this, false));
            t.setOnClickListener(v -> selectTab(idx));
            t.setVisibility(tabShown(i) ? View.VISIBLE : View.GONE);
            tabStrip.addView(t);
        }
        hs.addView(tabStrip);
        root.addView(hs, Ui.matchWrap());

        // Detail header
        header = new LinearLayout(this);
        Ui.center(header);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        header.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> goBack()));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        headerTitle = Ui.text(this, "", 18, R.color.text_primary, true);
        headerSub = Ui.text(this, "", 13, R.color.text_muted, false);
        ht.addView(headerTitle);
        ht.addView(headerSub);
        header.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Ui.icon(this, R.drawable.ic_shuffle, "Shuffle", v -> remote.play(pageSongs, 0, true)));
        header.addView(Ui.icon(this, R.drawable.ic_play, "Play", v -> remote.play(pageSongs, 0, false)));
        header.addView(Ui.icon(this, R.drawable.ic_more, "More", this::headerMenu));
        header.setBackgroundColor(Ui.color(this, R.color.bg_secondary));
        root.addView(header, Ui.matchWrap());

        // List, empty text and permission panel stacked
        FrameLayout body = new FrameLayout(this);
        list = new RecyclerView(this);
        lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 8));
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                if (adapter.getItemViewType(b.getBindingAdapterPosition()) != RowAdapter.SONG) return false;
                adapter.move(a.getBindingAdapterPosition(), b.getBindingAdapterPosition());
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) { }
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                savePlaylistOrder();
            }
        });
        dragger.attachToRecyclerView(list);

        FastScroller scroller = new FastScroller(this, list, lm);
        scroller.setLabeler(this::sectionLabel);
        body.addView(scroller, new FrameLayout.LayoutParams(scroller.preferredWidth(),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        fastScroller = scroller;

        emptyText = Ui.text(this, "", 15, R.color.text_muted, false);
        emptyText.setSingleLine(false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        body.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        permissionPanel = new LinearLayout(this);
        permissionPanel.setOrientation(LinearLayout.VERTICAL);
        permissionPanel.setGravity(Gravity.CENTER);
        permissionPanel.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        TextView pt = Ui.text(this, "Lyre reads the music Android has already found on your phone. "
                + "It needs the \"Music and audio\" permission for that.", 15, R.color.text_secondary, false);
        pt.setSingleLine(false);
        pt.setGravity(Gravity.CENTER);
        permissionPanel.addView(pt, Ui.matchWrap());
        LinearLayout.LayoutParams bp = Ui.wrap();
        bp.topMargin = Ui.dp(this, 16);
        permissionPanel.addView(Ui.button(this, "Allow access", v -> askPermission()), bp);
        permissionPanel.setBackgroundColor(Ui.color(this, R.color.bg_primary));
        body.addView(permissionPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildMini(), Ui.matchWrap());
        return root;
    }

    private View buildMini() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.color(this, R.color.bg_secondary));
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(1000);
        miniProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        box.addView(miniProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));
        LinearLayout row = new LinearLayout(this);
        Ui.center(row);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        miniArt = new ImageView(this);
        miniArt.setClipToOutline(true);
        miniArt.setBackground(Ui.rounded(this, R.color.surface, 6));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        ap.rightMargin = Ui.dp(this, 12);
        row.addView(miniArt, ap);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.text(this, "", 15, R.color.text_primary, true);
        miniArtist = Ui.text(this, "", 13, R.color.text_muted, false);
        texts.addView(miniTitle);
        texts.addView(miniArtist);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.icon(this, R.drawable.ic_queue, "Queue", v -> startActivity(
                new Intent(this, NowPlayingActivity.class).putExtra(NowPlayingActivity.EXTRA_QUEUE, true))));
        miniPlay = Ui.icon(this, R.drawable.ic_play, "Play/pause", v -> Remote.togglePlay(remote.get()));
        row.addView(miniPlay);
        row.addView(Ui.icon(this, R.drawable.ic_next, "Next", v -> {
            MediaController c = remote.get();
            if (c != null && c.hasNextMediaItem()) c.seekToNextMediaItem();
        }));
        box.addView(row);
        box.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
        mini = box;
        mini.setVisibility(View.GONE);
        return box;
    }

    // ---------------------------------------------------------------- mini player

    private void updateMini() {
        MediaController c = remote.get();
        MediaItem item = c == null ? null : c.getCurrentMediaItem();
        if (item == null) { mini.setVisibility(View.GONE); return; }
        mini.setVisibility(View.VISIBLE);
        Song s = Remote.song(item);
        CharSequence t = item.mediaMetadata.title, a = item.mediaMetadata.artist;
        miniTitle.setText(s != null ? s.title : t == null ? "" : t);
        miniArtist.setText(s != null ? s.artist : a == null ? "" : a);
        Art.into(miniArt, s, 96, R.drawable.ic_note);
        miniPlay.setImageResource(c.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        updateProgress();
        // Re-highlight the playing song in the list.
        String id = item.mediaId;
        boolean changed = false;
        for (RowAdapter.Row r : adapter.rows()) {
            boolean cur = r.type == RowAdapter.SONG && r.song != null && String.valueOf(r.song.id).equals(id);
            if (cur != r.current) { r.current = cur; changed = true; }
        }
        if (changed) adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    }

    private void updateProgress() {
        MediaController c = remote.get();
        if (c == null || mini.getVisibility() != View.VISIBLE) return;
        long d = c.getDuration();
        miniProgress.setProgress(d > 0 ? (int) (1000 * c.getCurrentPosition() / d) : 0);
    }

    // ---------------------------------------------------------------- navigation

    private Page top() { return stack.get(stack.size() - 1); }

    private boolean tabShown(int i) { return (prefs.tabsShown() & (1 << i)) != 0; }

    private int firstShownTab() {
        for (int i = 0; i < TABS.length; i++) if (tabShown(i)) return i;
        return 0;
    }

    private void selectTab(int idx) {
        closeSearch(false);
        stack.clear();
        stack.add(new Page(TABS[idx], null));
        prefs.tab(idx);
        render();
        list.scrollToPosition(0);
    }

    private void push(Page p) {
        top().scroll = lm.onSaveInstanceState();
        stack.add(p);
        render();
        list.scrollToPosition(0);
    }

    private void goBack() {
        if (searchBar.getVisibility() == View.VISIBLE) { closeSearch(true); return; }
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            render();
            Parcelable s = top().scroll;
            if (s != null) lm.onRestoreInstanceState(s);
        }
    }

    private void updateBack() {
        boolean need = stack.size() > 1 || searchBar.getVisibility() == View.VISIBLE;
        if (need && !backRegistered) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
            backRegistered = true;
        } else if (!need && backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
            backRegistered = false;
        }
    }

    private void openSearch() {
        searchBar.setVisibility(View.VISIBLE);
        searchChips.setVisibility(View.VISIBLE);
        searchBox.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        render();
    }

    private void closeSearch(boolean rerender) {
        if (searchBar.getVisibility() != View.VISIBLE) return;
        searchBar.setVisibility(View.GONE);
        searchChips.setVisibility(View.GONE);
        searchFilter = 0;
        searchBox.setText("");
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        if (rerender) render();
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        if (list == null) return;
        boolean perm = library.hasPermission();
        permissionPanel.setVisibility(perm ? View.GONE : View.VISIBLE);
        boolean searching = searchBar.getVisibility() == View.VISIBLE;
        Page p = searching ? new Page(SEARCH, null) : top();

        // Tab highlight
        int baseType = stack.get(0).type;
        for (int i = 0; i < TABS.length; i++) {
            TextView t = (TextView) tabStrip.getChildAt(i);
            t.setTextColor(Ui.color(this, TABS[i] == baseType ? R.color.accent : R.color.text_muted));
        }
        sortButton.setVisibility(!searching && (p.type == T_SONGS || p.type == T_ALBUMS) ? View.VISIBLE : View.GONE);

        List<RowAdapter.Row> rows = new ArrayList<>();
        pageSongs = new ArrayList<>();
        pageGroup = null;
        String empty = "";
        boolean detail = false;
        adapter.setDragStarter(null);

        switch (p.type) {
            case T_SONGS: {
                List<Song> s = new ArrayList<>(library.songs());
                library.sortSongs(s, prefs.songSort());
                if (prefs.songSortReversed()) java.util.Collections.reverse(s);
                pageSongs = s;
                if (!s.isEmpty()) rows.add(songBar(s));
                addSongs(rows, s, false, null);
                empty = !library.isLoaded() ? "Loading…" : emptyLibraryText();
                break;
            }
            case T_ALBUMS:
                for (Library.Group g : library.albums()) rows.add(groupRow(Actions.ALBUM, g, g.sub, Library.plural(g.songs.size(), "song")));
                empty = emptyLibraryText();
                break;
            case T_ARTISTS:
                for (Library.Group g : library.artists()) rows.add(groupRow(Actions.ARTIST, g, g.sub, ""));
                empty = emptyLibraryText();
                break;
            case T_GENRES:
                for (Library.Group g : library.genres()) rows.add(groupRow(Actions.GENRE, g, g.sub, ""));
                empty = emptyLibraryText();
                break;
            case T_FOLDERS:
                for (Library.Group g : library.folders()) rows.add(groupRow(Actions.FOLDER, g, g.sub, String.valueOf(g.songs.size())));
                empty = emptyLibraryText();
                break;
            case T_PLAYLISTS:
                rows.add(RowAdapter.Row.action(R.drawable.ic_add, "New playlist", v ->
                        Ui.input(this, "New playlist", "", "Create", n -> { db.createPlaylist(n); render(); })));
                rows.add(RowAdapter.Row.action(R.drawable.ic_smart, "New dynamic playlist", v ->
                        Ui.choose(this, "Start from", Smart.PRESET_NAMES, -1, w -> openSmartEditor(-1, w))));
                rows.add(RowAdapter.Row.action(R.drawable.ic_playlist, "Import M3U…", v -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{PlaylistIo.MIME, "audio/mpegurl",
                                    "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "application/octet-stream"});
                    startActivityForResult(i, REQ_IMPORT);
                }));
                rows.add(RowAdapter.Row.action(R.drawable.ic_folder, "Export all to a folder…", v ->
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_ALL)));
                {
                    List<Song> favs = library.resolve(db.favorites());
                    RowAdapter.Row fr = new RowAdapter.Row();
                    fr.type = RowAdapter.GROUP;
                    fr.t1 = "Favorites";
                    fr.t2 = Library.plural(favs.size(), "song");
                    fr.icon = R.drawable.ic_heart;
                    fr.click = v -> push(new Page(Actions.FAVORITES, "fav"));
                    fr.menu = anchor -> {
                        PopupMenu m = new PopupMenu(this, anchor);
                        m.getMenu().add(0, 1, 0, "Play");
                        m.getMenu().add(0, 2, 0, "Shuffle");
                        m.getMenu().add(0, 3, 0, "Export M3U…");
                        m.setOnMenuItemClickListener(it -> {
                            List<Song> f = library.resolve(db.favorites());
                            if (it.getItemId() == 1) remote.play(f, 0, false);
                            else if (it.getItemId() == 2) remote.play(f, 0, true);
                            else exportFavorites();
                            return true;
                        });
                        m.show();
                    };
                    rows.add(RowAdapter.Row.header("Favorites"));
                    rows.add(fr);
                }
                List<Db.SmartRow> smarts = db.smartPlaylists();
                if (!smarts.isEmpty()) rows.add(RowAdapter.Row.header("Dynamic playlists"));
                for (Db.SmartRow sr : smarts) rows.add(smartRow(sr));
                List<Db.Playlist> pls = db.playlists();
                if (!pls.isEmpty()) rows.add(RowAdapter.Row.header("Playlists"));
                for (Db.Playlist pl : pls) rows.add(playlistRow(pl));
                break;
            case Actions.ALBUM:
            case Actions.GENRE:
            case Actions.FOLDER: {
                Library.Group g = p.type == Actions.ALBUM ? library.album(p.key)
                        : p.type == Actions.GENRE ? library.genre(p.key) : library.folder(p.key);
                detail = true;
                if (g == null) { empty = "Nothing here any more."; headerTitle.setText(""); headerSub.setText(""); break; }
                pageGroup = g;
                pageSongs = g.songs;
                headerTitle.setText(g.name);
                Song f = g.first();
                headerSub.setText((p.type == Actions.ALBUM ? g.sub + (f != null && f.year > 0 ? " · " + f.year : "") + " · "
                        : p.type == Actions.FOLDER ? g.key + " · " : "")
                        + Library.plural(g.songs.size(), "song") + " · " + Library.time(g.duration()));
                addSongs(rows, g.songs, p.type == Actions.ALBUM, null);
                break;
            }
            case Actions.ARTIST: {
                Library.Group g = library.artist(p.key);
                detail = true;
                if (g == null) { empty = "Nothing here any more."; headerTitle.setText(""); headerSub.setText(""); break; }
                pageGroup = g;
                pageSongs = g.songs;
                headerTitle.setText(g.name);
                headerSub.setText(g.sub);
                List<Library.Group> albums = library.albumsBy(p.key);
                if (!albums.isEmpty()) rows.add(RowAdapter.Row.header("Albums"));
                for (Library.Group a : albums) {
                    Song f = a.first();
                    rows.add(groupRow(Actions.ALBUM, a, f != null && f.year > 0 ? String.valueOf(f.year) : "",
                            Library.plural(a.songs.size(), "song")));
                }
                rows.add(RowAdapter.Row.header("Songs"));
                addSongs(rows, g.songs, false, null);
                break;
            }
            case Actions.PLAYLIST: {
                long id = Long.parseLong(p.key);
                String name = db.playlistName(id);
                detail = true;
                if (name == null) { empty = "This playlist was deleted."; headerTitle.setText(""); headerSub.setText(""); break; }
                List<String> paths = db.playlistPaths(id);
                List<Song> songs = library.resolve(paths);
                pageSongs = songs;
                headerTitle.setText(name);
                long dur = 0;
                for (Song s : songs) dur += s.duration;
                int missing = paths.size() - songs.size();
                headerSub.setText(Library.plural(songs.size(), "song") + " · " + Library.time(dur)
                        + (missing > 0 ? " · " + missing + " missing" : ""));
                addSongs(rows, songs, false, id);
                if (missing == 0) adapter.setDragStarter(h -> dragger.startDrag(h));
                empty = "Empty playlist. Add songs from their ⋮ menu.";
                break;
            }
            case Actions.FAVORITES: {
                detail = true;
                List<String> paths = db.favorites();
                List<Song> songs = library.resolve(paths);
                pageSongs = songs;
                long dur = 0;
                for (Song x : songs) dur += x.duration;
                headerTitle.setText("Favorites");
                headerSub.setText(Library.plural(songs.size(), "song") + " · " + Library.time(dur) + " · newest first");
                addSongs(rows, songs, false, null);
                empty = "No favorites yet. Tap the heart on Now Playing, or use a song's ⋮ menu.";
                break;
            }
            case Actions.SMART: {
                long id = Long.parseLong(p.key);
                Smart sm = db.smart(id);
                detail = true;
                if (sm == null) { empty = "This playlist was deleted."; headerTitle.setText(""); headerSub.setText(""); break; }
                if (p.cache == null) p.cache = sm.evaluate(library.songs(), db.stats(), System.currentTimeMillis());
                List<Song> songs = p.cache;
                pageSongs = songs;
                long dur = 0;
                for (Song s : songs) dur += s.duration;
                headerTitle.setText(sm.name);
                headerSub.setText(Library.plural(songs.size(), "song") + " · " + Library.time(dur) + " · " + sm.describe());
                addSongs(rows, songs, false, null);
                empty = "No songs match these rules yet.";
                break;
            }
            case SEARCH: {
                for (int i = 0; i < searchChips.getChildCount(); i++) {
                    TextView c = (TextView) searchChips.getChildAt(i);
                    boolean on = i == searchFilter;
                    c.setTextColor(Ui.color(this, on ? R.color.accent : R.color.text_muted));
                    c.setBackground(on ? Ui.rounded(this, R.color.surface, 16) : null);
                }
                String q = searchBox.getText().toString();
                if (q.trim().isEmpty()) { empty = "Type to search."; break; }
                boolean all = searchFilter == 0;
                if (all || searchFilter == 2) {
                    List<Library.Group> ar = Library.searchGroups(library.artists(), q);
                    int max = all ? 8 : Integer.MAX_VALUE;
                    if (!ar.isEmpty() && all) rows.add(RowAdapter.Row.header("Artists"));
                    for (int i = 0; i < ar.size() && i < max; i++) rows.add(groupRow(Actions.ARTIST, ar.get(i), ar.get(i).sub, ""));
                    if (all && ar.size() > max) rows.add(moreRow(ar.size() - max, 2));
                }
                if (all || searchFilter == 3) {
                    List<Library.Group> al = Library.searchGroups(library.albums(), q);
                    int max = all ? 12 : Integer.MAX_VALUE;
                    if (!al.isEmpty() && all) rows.add(RowAdapter.Row.header("Albums"));
                    for (int i = 0; i < al.size() && i < max; i++) rows.add(groupRow(Actions.ALBUM, al.get(i), al.get(i).sub, ""));
                    if (all && al.size() > max) rows.add(moreRow(al.size() - max, 3));
                }
                if (all || searchFilter == 1) {
                    List<Song> so = library.searchSongs(q);
                    if (!so.isEmpty()) {
                        if (all) rows.add(RowAdapter.Row.header("Songs"));
                        rows.add(songBar(so));
                    }
                    int max = all ? 50 : 5000;
                    if (all && so.size() > max) {
                        pageSongs = so;
                        addSongs(rows, so.subList(0, max), false, null);
                        rows.add(moreRow(so.size() - max, 1));
                    } else {
                        if (so.size() > max) so = so.subList(0, max);
                        pageSongs = so;
                        addSongs(rows, so, false, null);
                    }
                }
                empty = "No matches.";
                break;
            }
            default:
                break;
        }

        header.setVisibility(detail && !searching ? View.VISIBLE : View.GONE);
        tabStrip.setVisibility(View.VISIBLE);
        adapter.set(rows);
        list.post(() -> fastScroller.sync());
        emptyText.setText(perm && rows.isEmpty() ? empty : "");
        emptyText.setVisibility(perm && rows.isEmpty() ? View.VISIBLE : View.GONE);
        updateBack();
        updateMini();
    }

    /** Bubble text while dragging the scrollbar: first letter (or year/number) of the row there. */
    private String sectionLabel(int pos) {
        List<RowAdapter.Row> rows = adapter.rows();
        for (int i = pos; i < rows.size(); i++) {
            RowAdapter.Row r = rows.get(i);
            if (r.type != RowAdapter.SONG && r.type != RowAdapter.GROUP) continue;
            if (top().type == T_SONGS && r.song != null) {
                switch (prefs.songSort()) {
                    case Library.SORT_ARTIST: return initial(r.song.artist);
                    case Library.SORT_ALBUM: return initial(r.song.album);
                    case Library.SORT_YEAR: return r.song.year > 0 ? String.valueOf(r.song.year) : "?";
                    case Library.SORT_ADDED: return "";
                    default: return initial(r.song.title);
                }
            }
            return initial(r.t1);
        }
        return "";
    }

    private static String initial(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        int cp = t.codePointAt(0);
        return Character.isLetter(cp) ? new String(Character.toChars(Character.toUpperCase(cp)))
                : Character.isDigit(cp) ? "#" : t.substring(0, Character.charCount(cp));
    }

    private String emptyLibraryText() {
        if (!library.isLoaded()) return "Loading…";
        if (library.totalCount() > 0 && library.hiddenCount() == library.totalCount())
            return "Everything is hidden by the blacklist.";
        return "No music found. Android's media scanner hasn't found any music files, "
                + "or they're filtered out in Settings.";
    }

    /** Adds song rows. playlistId non-null adds "Remove from playlist" to the menus. */
    private void addSongs(List<RowAdapter.Row> rows, List<Song> songs, boolean albumView, Long playlistId) {
        final List<Song> queue = songs;
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            final int idx = i;
            RowAdapter.Row r = new RowAdapter.Row();
            r.type = RowAdapter.SONG;
            r.song = s;
            r.tag = s;
            if (albumView) {
                r.t1 = (s.track > 0 ? s.track + ". " : "") + s.title;
                r.t2 = s.artist.equals(s.albumArtist) ? "" : s.artist;
            } else {
                r.t1 = s.title;
                r.t2 = s.artist + " · " + s.album;
            }
            if (library.isHidden(s)) r.t2 = "⊘ Blacklisted · " + r.t2;
            r.t3 = Library.time(s.duration);
            r.click = v -> remote.play(queue, idx, false);
            r.menu = playlistId == null
                    ? anchor -> Actions.song(this, anchor, s, null, null)
                    : anchor -> Actions.song(this, anchor, s, "Remove from playlist", () -> removeFromPlaylist(playlistId, s));
            rows.add(r);
        }
    }

    private RowAdapter.Row groupRow(int kind, Library.Group g, String sub, String right) {
        RowAdapter.Row r = new RowAdapter.Row();
        r.type = RowAdapter.GROUP;
        r.t1 = g.name;
        r.t2 = sub;
        r.t3 = right;
        r.song = kind == Actions.ALBUM ? g.first() : null;
        r.icon = kind == Actions.ALBUM ? R.drawable.ic_album : kind == Actions.ARTIST ? R.drawable.ic_person
                : kind == Actions.FOLDER ? R.drawable.ic_folder : R.drawable.ic_note;
        r.click = v -> push(new Page(kind, g.key));
        r.menu = anchor -> Actions.group(this, anchor, kind, g);
        if (kind == Actions.ARTIST || kind == Actions.FOLDER || kind == Actions.GENRE) {
            // Show an album cover from the group, if any.
            r.song = g.first();
        }
        return r;
    }

    /** "N songs" bar with shuffle all, play all and a menu for everything else. */
    private RowAdapter.Row songBar(List<Song> songs) {
        final List<Song> all = songs;
        return RowAdapter.Row.bar(Library.plural(songs.size(), "song"),
                v -> remote.play(all, 0, false),
                v -> remote.play(all, 0, true),
                anchor -> bulkMenu(anchor, all));
    }

    /** "N more…" row that switches the search to one result type. */
    private RowAdapter.Row moreRow(int n, int filter) {
        return RowAdapter.Row.action(R.drawable.ic_search, n + " more " + FILTERS[filter].toLowerCase(java.util.Locale.ROOT) + "…",
                v -> { searchFilter = filter; render(); list.scrollToPosition(0); });
    }

    /** Actions on every song in a result list. */
    private void bulkMenu(View anchor, List<Song> songs) {
        PopupMenu m = new PopupMenu(this, anchor);
        String n = Library.plural(songs.size(), "song");
        m.getMenu().add(0, 1, 0, "Play all");
        m.getMenu().add(0, 2, 0, "Shuffle all");
        m.getMenu().add(0, 3, 0, "Play next");
        m.getMenu().add(0, 4, 0, "Add to queue");
        m.getMenu().add(0, 5, 0, "Add all to playlist…");
        m.getMenu().add(0, 8, 0, "Add all to favorites");
        m.getMenu().add(0, 6, 0, "Edit tags (" + n + ")");
        m.getMenu().add(0, 7, 0, "Blacklist all");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: remote.play(songs, 0, false); break;
                case 2: remote.play(songs, 0, true); break;
                case 3: remote.playNext(songs); Ui.toast(this, "Playing next"); break;
                case 4: remote.enqueue(songs); Ui.toast(this, "Added to queue"); break;
                case 5: Actions.addToPlaylist(this, songs); break;
                case 6: Actions.editTags(this, songs); break;
                case 8: Actions.favoriteAll(this, songs); break;
                case 7:
                    Ui.confirm(this, "Blacklist " + n + "?", "Each song is added to the blacklist. You can remove them "
                            + "in Settings → Blacklist, or switch the blacklist off to show them again.", "Blacklist", () -> {
                        List<String[]> entries = new ArrayList<>();
                        for (Song s : songs) entries.add(new String[]{Db.BL_SONG, s.path, s.title + " — " + s.artist});
                        db.addBlacklist(entries);
                        library.refilter();
                        Ui.toast(this, prefs.blacklistOn() ? "Blacklisted " + n : "Added " + n + " (the blacklist is switched off)");
                    });
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private RowAdapter.Row smartRow(Db.SmartRow sr) {
        RowAdapter.Row r = new RowAdapter.Row();
        r.type = RowAdapter.GROUP;
        r.t1 = sr.smart.name;
        r.t2 = sr.smart.describe();
        r.icon = R.drawable.ic_smart;
        r.click = v -> push(new Page(Actions.SMART, String.valueOf(sr.id)));
        r.menu = anchor -> smartMenu(anchor, sr.id);
        return r;
    }

    private boolean exportFavs;

    private void exportFavorites() {
        exportFavs = true;
        exportPlaylistId = -1;
        exportSmartId = -1;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(PlaylistIo.MIME)
                .putExtra(Intent.EXTRA_TITLE, "Favorites.m3u"), REQ_EXPORT);
    }

    private void openSmartEditor(long id, int preset) {
        smartDirty = true;
        startActivity(new Intent(this, SmartEditorActivity.class)
                .putExtra(SmartEditorActivity.EXTRA_ID, id)
                .putExtra(SmartEditorActivity.EXTRA_PRESET, preset));
    }

    /** Current contents of a dynamic playlist (the open page's, so Play matches what you see). */
    private List<Song> smartSongs(long id) {
        Page t = top();
        if (t.type == Actions.SMART && t.key.equals(String.valueOf(id)) && t.cache != null) return t.cache;
        Smart sm = db.smart(id);
        return sm == null ? new ArrayList<>() : sm.evaluate(library.songs(), db.stats(), System.currentTimeMillis());
    }

    private void smartMenu(View anchor, long id) {
        Smart sm = db.smart(id);
        if (sm == null) return;
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play");
        m.getMenu().add(0, 2, 0, "Shuffle");
        m.getMenu().add(0, 3, 0, "Play next");
        m.getMenu().add(0, 4, 0, "Add to queue");
        m.getMenu().add(0, 5, 0, "Edit rules");
        m.getMenu().add(0, 6, 0, "Refresh");
        m.getMenu().add(0, 7, 0, "Save as regular playlist");
        m.getMenu().add(0, 8, 0, "Export M3U…");
        m.getMenu().add(0, 9, 0, "Delete");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: remote.play(smartSongs(id), 0, false); break;
                case 2: remote.play(smartSongs(id), 0, true); break;
                case 3: remote.playNext(smartSongs(id)); break;
                case 4: remote.enqueue(smartSongs(id)); break;
                case 5: openSmartEditor(id, 0); break;
                case 6: for (Page p : stack) p.cache = null; render(); break;
                case 7: {
                    List<Song> songs = smartSongs(id);
                    Ui.input(this, "Save as playlist", sm.name, "Save", n -> {
                        long pid = db.createPlaylist(n);
                        List<String> paths = new ArrayList<>();
                        for (Song s : songs) paths.add(s.path);
                        db.addToPlaylist(pid, paths);
                        Ui.toast(this, "Saved " + Library.plural(paths.size(), "song"));
                        render();
                    });
                    break;
                }
                case 8:
                    exportSmartId = id;
                    exportPlaylistId = -1;
                    exportFavs = false;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(PlaylistIo.MIME)
                            .putExtra(Intent.EXTRA_TITLE, PlaylistIo.safeName(sm.name) + ".m3u"), REQ_EXPORT);
                    break;
                case 9:
                    Ui.confirm(this, "Delete dynamic playlist?", "\"" + sm.name + "\" will be deleted. Your music files aren't touched.",
                            "Delete", () -> {
                                db.deleteSmart(id);
                                if (top().type == Actions.SMART) goBack(); else render();
                            });
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private RowAdapter.Row playlistRow(Db.Playlist pl) {
        RowAdapter.Row r = new RowAdapter.Row();
        r.type = RowAdapter.GROUP;
        r.t1 = pl.name;
        r.t2 = Library.plural(pl.count, "song");
        r.icon = R.drawable.ic_playlist;
        r.click = v -> push(new Page(Actions.PLAYLIST, String.valueOf(pl.id)));
        r.menu = anchor -> playlistMenu(anchor, pl.id, pl.name);
        return r;
    }

    // ---------------------------------------------------------------- menus

    private void headerMenu(View anchor) {
        Page p = top();
        if (p.type == Actions.FAVORITES) {
            PopupMenu m = new PopupMenu(this, anchor);
            m.getMenu().add(0, 1, 0, "Play next");
            m.getMenu().add(0, 2, 0, "Add to queue");
            m.getMenu().add(0, 3, 0, "Add all to playlist…");
            m.getMenu().add(0, 4, 0, "Export M3U…");
            m.setOnMenuItemClickListener(it -> {
                switch (it.getItemId()) {
                    case 1: remote.playNext(pageSongs); break;
                    case 2: remote.enqueue(pageSongs); break;
                    case 3: Actions.addToPlaylist(this, pageSongs); break;
                    default: exportFavorites(); break;
                }
                return true;
            });
            m.show();
        } else if (p.type == Actions.PLAYLIST) {
            playlistMenu(anchor, Long.parseLong(p.key), db.playlistName(Long.parseLong(p.key)));
        } else if (p.type == Actions.SMART) {
            smartMenu(anchor, Long.parseLong(p.key));
        } else if (pageGroup != null) {
            Actions.group(this, anchor, p.type, pageGroup);
        }
    }

    private void playlistMenu(View anchor, long id, String name) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play");
        m.getMenu().add(0, 2, 0, "Shuffle");
        m.getMenu().add(0, 3, 0, "Play next");
        m.getMenu().add(0, 4, 0, "Add to queue");
        m.getMenu().add(0, 5, 0, "Rename");
        m.getMenu().add(0, 6, 0, "Export M3U…");
        m.getMenu().add(0, 7, 0, "Delete");
        m.setOnMenuItemClickListener(it -> {
            List<Song> songs = library.resolve(db.playlistPaths(id));
            switch (it.getItemId()) {
                case 1: remote.play(songs, 0, false); break;
                case 2: remote.play(songs, 0, true); break;
                case 3: remote.playNext(songs); break;
                case 4: remote.enqueue(songs); break;
                case 5: Ui.input(this, "Rename playlist", name, "Rename", n -> { db.renamePlaylist(id, n); render(); }); break;
                case 6:
                    exportPlaylistId = id;
                    exportSmartId = -1;
                    exportFavs = false;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(PlaylistIo.MIME)
                            .putExtra(Intent.EXTRA_TITLE, PlaylistIo.safeName(name) + ".m3u"), REQ_EXPORT);
                    break;
                case 7:
                    Ui.confirm(this, "Delete playlist?", "\"" + name + "\" will be deleted. Your music files aren't touched.",
                            "Delete", () -> {
                                db.deletePlaylist(id);
                                if (top().type == Actions.PLAYLIST) goBack(); else render();
                            });
                    break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    // Natural direction label, reversed label.
    private static final String[][] SONG_SORTS = {{"Title", "A→Z", "Z→A"}, {"Artist", "A→Z", "Z→A"},
            {"Album", "A→Z", "Z→A"}, {"Date added", "newest first", "oldest first"}, {"Year", "newest first", "oldest first"}};
    private static final String[][] ALBUM_SORTS = {{"Title", "A→Z", "Z→A"}, {"Artist", "A→Z", "Z→A"},
            {"Year", "newest first", "oldest first"}};

    /** Pick a sort; picking the current one again flips its direction. */
    private void sortMenu() {
        Page p = top();
        boolean songs = p.type == T_SONGS;
        if (!songs && p.type != T_ALBUMS) return;
        String[][] sorts = songs ? SONG_SORTS : ALBUM_SORTS;
        int cur = songs ? prefs.songSort() : prefs.albumSort();
        boolean rev = songs ? prefs.songSortReversed() : prefs.albumSortReversed();
        String[] items = new String[sorts.length];
        for (int i = 0; i < sorts.length; i++) {
            items[i] = i == cur ? sorts[i][0] + " (" + sorts[i][rev ? 2 : 1] + ")  ⇅ tap again to reverse"
                    : sorts[i][0] + " (" + sorts[i][1] + ")";
        }
        Ui.choose(this, songs ? "Sort songs" : "Sort albums", items, cur, w -> {
            boolean r = w == cur && !rev;
            if (songs) { prefs.songSort(w); prefs.songSortReversed(r); render(); }
            else { prefs.albumSort(w); prefs.albumSortReversed(r); library.refilter(); }
            Ui.toast(this, sorts[w][0] + ", " + sorts[w][r ? 2 : 1]);
        });
    }

    // ---------------------------------------------------------------- playlists

    private void removeFromPlaylist(long id, Song s) {
        List<String> paths = db.playlistPaths(id);
        paths.remove(s.path);
        db.setPlaylist(id, paths);
        render();
    }

    /** After a drag in a playlist page, store the new order. */
    private void savePlaylistOrder() {
        Page p = top();
        if (p.type != Actions.PLAYLIST) return;
        List<String> paths = new ArrayList<>();
        for (RowAdapter.Row r : adapter.rows()) if (r.type == RowAdapter.SONG && r.song != null) paths.add(r.song.path);
        db.setPlaylist(Long.parseLong(p.key), paths);
        List<Song> s = new ArrayList<>();
        for (RowAdapter.Row r : adapter.rows()) if (r.song != null) s.add(r.song);
        pageSongs = s;
        // Rebind so each row plays from its new position.
        render();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT && (exportPlaylistId >= 0 || exportSmartId >= 0 || exportFavs)) {
                List<Song> songs = exportFavs ? library.resolve(db.favorites())
                        : exportSmartId >= 0 ? smartSongs(exportSmartId)
                        : library.resolve(db.playlistPaths(exportPlaylistId));
                exportFavs = false;
                PlaylistIo.export(getContentResolver(), uri, songs, prefs.m3uRelative());
                Ui.toast(this, "Exported " + Library.plural(songs.size(), "song"));
            } else if (req == REQ_IMPORT) {
                // Importing is deliberate, so blacklisted songs come in too.
                PlaylistIo.Imported im = PlaylistIo.read(getContentResolver(), uri, library.all());
                String name = PlaylistIo.nameFromDocument(getContentResolver(), uri);
                long id = db.createPlaylist(name);
                db.addToPlaylist(id, im.paths);
                Ui.toast(this, "Imported " + Library.plural(im.paths.size(), "song")
                        + (im.missing > 0 ? ", " + im.missing + " not found" : ""));
                render();
            } else if (req == REQ_EXPORT_ALL) {
                int n = 0;
                for (Db.Playlist pl : db.playlists()) {
                    PlaylistIo.exportToTree(getContentResolver(), uri, pl.name,
                            library.resolve(db.playlistPaths(pl.id)), prefs.m3uRelative());
                    n++;
                }
                for (Db.SmartRow sr : db.smartPlaylists()) {
                    PlaylistIo.exportToTree(getContentResolver(), uri, sr.smart.name,
                            sr.smart.evaluate(library.songs(), db.stats(), System.currentTimeMillis()), prefs.m3uRelative());
                    n++;
                }
                Ui.toast(this, "Exported " + Library.plural(n, "playlist"));
            }
        } catch (Exception e) {
            Ui.toast(this, "Failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- permission

    private void askPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO) || !prefs.raw().getBoolean("asked_perm", false)) {
            prefs.raw().edit().putBoolean("asked_perm", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQ_PERM);
        } else {
            // Denied for good: only the system settings can grant it now.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        prefs.raw().edit().putBoolean("asked_perm", true).apply();
        if (req == REQ_PERM && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            library.reload();
            remote.connect(c -> updateMini());
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (themeVersion != Theme.version) { recreate(); return; }
        // Coming back from system settings with the permission granted.
        if (library.hasPermission() && !library.isLoaded()) {
            library.reload();
            remote.connect(c -> updateMini());
        }
    }
}
