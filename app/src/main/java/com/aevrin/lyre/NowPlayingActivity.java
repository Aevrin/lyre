/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Full-screen player with the queue, sleep timer and normalization status. */
public class NowPlayingActivity extends Activity implements Actions.Host {

    private int themeVersion;

    private Remote remote;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView art;
    private TextView title, artist, album, pos, dur, status;
    private SeekBar seek;
    private ImageButton play, shuffle, repeat, queueBtn, heart;
    private FrameLayout stage;
    private RecyclerView queueList;
    private RowAdapter queueAdapter;
    private ItemTouchHelper queueDragger;
    private LinearLayout queueBar;
    private boolean dragging, showQueue;
    private String shownId = "";
    // Queue rows map to these player indices (play order when shuffled).
    private List<Integer> order = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updatePosition();
            updateStatus();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        themeVersion = Theme.version;
        setContentView(build());
        remote = new Remote(this, new Player.Listener() {
            @Override public void onEvents(@NonNull Player p, @NonNull Player.Events e) {
                update();
                if (showQueue && (e.contains(Player.EVENT_TIMELINE_CHANGED)
                        || e.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        || e.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED))) renderQueue();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        remote.connect(c -> { update(); if (showQueue) renderQueue(); });
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
        remote.release();
    }

    @Override public Activity activity() { return this; }
    @Override public Remote remote() { return remote; }
    @Override public void refresh() { update(); }
    @Override
    public void openGroup(int kind, String key) {
        startActivity(new android.content.Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_KIND, kind)
                .putExtra(MainActivity.EXTRA_KEY, key));
        finish();
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        top.addView(Ui.icon(this, R.drawable.ic_back, "Back", v -> finish()));
        TextView label = Ui.text(this, "Now playing", 16, R.color.text_secondary, true);
        top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.icon(this, R.drawable.ic_eq, "Equalizer",
                v -> startActivity(new android.content.Intent(this, EqualizerActivity.class))));
        top.addView(Ui.icon(this, R.drawable.ic_timer, "Sleep timer", v -> sleepMenu()));
        queueBtn = Ui.icon(this, R.drawable.ic_queue, "Queue", v -> toggleQueue());
        top.addView(queueBtn);
        top.addView(Ui.icon(this, R.drawable.ic_more, "More", this::moreMenu));
        root.addView(top, Ui.matchWrap());

        // Art or queue
        stage = new FrameLayout(this);
        art = new ImageView(this) {
            @Override protected void onMeasure(int w, int h) {
                // Square, as large as fits.
                int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
                setMeasuredDimension(size, size);
            }
        };
        art.setClipToOutline(true);
        art.setBackground(Ui.rounded(this, R.color.surface, 12));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        int m = Ui.dp(this, 24);
        alp.setMargins(m, Ui.dp(this, 8), m, Ui.dp(this, 8));
        stage.addView(art, alp);

        LinearLayout queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queueBar = new LinearLayout(this);
        Ui.center(queueBar);
        queueBar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);
        queueBar.addView(Ui.text(this, "Queue", 15, R.color.accent, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = Ui.text(this, "Save as playlist", 14, R.color.accent, false);
        save.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        save.setBackground(Ui.ripple(this, false));
        save.setOnClickListener(v -> saveQueueAsPlaylist());
        queueBar.addView(save);
        TextView clear = Ui.text(this, "Clear", 14, R.color.accent, false);
        clear.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        clear.setBackground(Ui.ripple(this, false));
        clear.setOnClickListener(v -> clearQueue());
        queueBar.addView(clear);
        queueBox.addView(queueBar, Ui.matchWrap());
        queueList = new RecyclerView(this);
        queueList.setLayoutManager(new LinearLayoutManager(this));
        queueAdapter = new RowAdapter();
        queueList.setAdapter(queueAdapter);
        queueBox.addView(queueList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        queueBox.setVisibility(View.GONE);
        stage.addView(queueBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ItemTouchHelper th = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            int from = -1, to = -1;
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                int f = a.getBindingAdapterPosition(), t = b.getBindingAdapterPosition();
                if (from < 0) from = f;
                to = t;
                queueAdapter.move(f, t);
                return true;
            }
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {
                int p = h.getBindingAdapterPosition();
                MediaController c = remote.get();
                if (c == null || p < 0 || p >= order.size()) { renderQueue(); return; }
                int idx = order.get(p);
                queueAdapter.remove(p);
                order.remove(p);
                c.removeMediaItem(idx);
            }
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override
            public int getDragDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                MediaController c = remote.get();
                return c != null && c.getShuffleModeEnabled() ? 0 : super.getDragDirs(rv, h);
            }
            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                MediaController c = remote.get();
                if (c != null && from >= 0 && to >= 0 && from != to) c.moveMediaItem(from, to);
                from = to = -1;
            }
        });
        th.attachToRecyclerView(queueList);
        queueDragger = th;
        queueAdapter.setDragStarter(th::startDrag);

        // Titles
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        title = Ui.text(this, "", 22, R.color.text_primary, true);
        artist = Ui.text(this, "", 16, R.color.text_secondary, false);
        album = Ui.text(this, "", 14, R.color.text_muted, false);
        title.setSelected(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        info.addView(title);
        info.addView(artist);
        info.addView(album);
        LinearLayout infoRow = new LinearLayout(this);
        Ui.center(infoRow);
        infoRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heart = Ui.icon(this, R.drawable.ic_heart_outline, "Favorite", v -> toggleFavorite());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        hp.rightMargin = Ui.dp(this, 12);
        infoRow.addView(heart, hp);
        root.addView(infoRow, Ui.matchWrap());

        // Seek bar
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    MediaController c = remote.get();
                    if (c != null) pos.setText(Library.time(c.getDuration() * p / 1000));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                MediaController c = remote.get();
                if (c != null && c.getDuration() > 0) c.seekTo(c.getDuration() * s.getProgress() / 1000);
            }
        });
        LinearLayout.LayoutParams sp = Ui.matchWrap();
        sp.setMargins(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), 0);
        root.addView(seek, sp);
        LinearLayout times = new LinearLayout(this);
        times.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        pos = Ui.text(this, "0:00", 12, R.color.text_muted, false);
        dur = Ui.text(this, "0:00", 12, R.color.text_muted, false);
        times.addView(pos);
        times.addView(Ui.spacer(this));
        times.addView(dur);
        root.addView(times, Ui.matchWrap());

        // Controls
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        shuffle = Ui.icon(this, R.drawable.ic_shuffle, "Shuffle", v -> {
            MediaController c = remote.get();
            if (c != null) c.setShuffleModeEnabled(!c.getShuffleModeEnabled());
        });
        ImageButton prev = Ui.icon(this, R.drawable.ic_prev, "Previous", v -> {
            MediaController c = remote.get();
            if (c != null) c.seekToPrevious();
        });
        play = Ui.icon(this, R.drawable.ic_play, "Play/pause", v -> Remote.togglePlay(remote.get()));
        play.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        play.setScaleType(ImageView.ScaleType.FIT_CENTER);
        play.setBackground(Ui.rounded(this, R.color.button, 36));
        play.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        int pp = Ui.dp(this, 18);
        play.setPadding(pp, pp, pp, pp);
        ImageButton next = Ui.icon(this, R.drawable.ic_next, "Next", v -> {
            MediaController c = remote.get();
            if (c != null) c.seekToNext();
        });
        repeat = Ui.icon(this, R.drawable.ic_repeat, "Repeat", v -> {
            MediaController c = remote.get();
            if (c == null) return;
            int r = c.getRepeatMode();
            c.setRepeatMode(r == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                    : r == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        });
        for (View v : new View[]{shuffle, prev, play, next, repeat}) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
            controls.addView(v, lp);
        }
        root.addView(controls, Ui.matchWrap());

        status = Ui.text(this, "", 12, R.color.text_muted, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        root.addView(status, Ui.matchWrap());
        return root;
    }

    // ---------------------------------------------------------------- state

    private void update() {
        MediaController c = remote.get();
        if (c == null) return;
        MediaItem item = c.getCurrentMediaItem();
        Song s = Remote.song(item);
        if (item == null) {
            title.setText("Nothing playing");
            artist.setText("");
            album.setText("");
        } else {
            CharSequence t = item.mediaMetadata.title;
            title.setText(s != null ? s.title : t == null ? "" : t);
            artist.setText(s != null ? s.artist : "");
            album.setText(s != null ? s.album + (s.year > 0 ? " · " + s.year : "") : "");
        }
        String id = item == null ? "" : item.mediaId;
        if (!id.equals(shownId)) {
            shownId = id;
            Art.into(art, s, 1024, R.drawable.ic_album);
        }
        play.setImageResource(c.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        updateHeart(s);
        shuffle.setAlpha(c.getShuffleModeEnabled() ? 1f : 0.4f);
        shuffle.setImageTintList(c.getShuffleModeEnabled() ? ColorStateList.valueOf(Ui.color(this, R.color.accent)) : null);
        int r = c.getRepeatMode();
        repeat.setImageResource(r == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setAlpha(r == Player.REPEAT_MODE_OFF ? 0.4f : 1f);
        repeat.setImageTintList(r == Player.REPEAT_MODE_OFF ? null : ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        updatePosition();
        updateStatus();
    }

    private void updatePosition() {
        MediaController c = remote.get();
        if (c == null || dragging) return;
        long d = c.getDuration(), p = c.getCurrentPosition();
        dur.setText(d > 0 ? Library.time(d) : "0:00");
        pos.setText(Library.time(p));
        seek.setProgress(d > 0 ? (int) (1000 * p / d) : 0);
    }

    private void updateStatus() {
        PlaybackService svc = PlaybackService.get();
        StringBuilder sb = new StringBuilder();
        if (svc != null) {
            Normalizer.Info i = svc.normInfo();
            if (LyreApp.get().prefs().normMode() == Prefs.NORM_OFF) {
                sb.append("Normalization off");
            } else {
                sb.append(String.format(Locale.ROOT, "Normalized %+.1f dB · %s", i.gainDb, i.source));
            }
            long left = svc.sleepRemaining();
            if (left == -1) sb.append("\nSleep: after this track");
            else if (left > 0) sb.append("\nSleep in ").append(Library.time(left));
        }
        status.setText(sb.toString());
        status.setSingleLine(false);
    }

    private void updateHeart(Song s) {
        boolean fav = s != null && LyreApp.get().db().isFavorite(s.path);
        heart.setVisibility(s == null ? View.INVISIBLE : View.VISIBLE);
        heart.setImageResource(fav ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
        heart.setImageTintList(fav ? ColorStateList.valueOf(Ui.color(this, R.color.accent)) : null);
        heart.setContentDescription(fav ? "Remove from favorites" : "Add to favorites");
    }

    private void toggleFavorite() {
        MediaController c = remote.get();
        Song s = c == null ? null : Remote.song(c.getCurrentMediaItem());
        if (s == null) return;
        Db db = LyreApp.get().db();
        db.setFavorite(s.path, !db.isFavorite(s.path));
        updateHeart(s);
        PlaybackService svc = PlaybackService.get();
        if (svc != null) svc.updateWidgets();
    }

    // ---------------------------------------------------------------- queue

    private void toggleQueue() {
        showQueue = !showQueue;
        art.setVisibility(showQueue ? View.GONE : View.VISIBLE);
        ((View) queueList.getParent()).setVisibility(showQueue ? View.VISIBLE : View.GONE);
        queueBtn.setImageTintList(showQueue ? ColorStateList.valueOf(Ui.color(this, R.color.accent)) : null);
        if (showQueue) renderQueue();
    }

    private void renderQueue() {
        MediaController c = remote.get();
        if (c == null) return;
        Timeline tl = c.getCurrentTimeline();
        boolean sh = c.getShuffleModeEnabled();
        order = new ArrayList<>();
        if (!tl.isEmpty()) {
            int i = tl.getFirstWindowIndex(sh);
            while (i != C.INDEX_UNSET && order.size() < tl.getWindowCount()) {
                order.add(i);
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, sh);
            }
        }
        int cur = c.getCurrentMediaItemIndex();
        List<RowAdapter.Row> rows = new ArrayList<>();
        int curRow = 0;
        for (int k = 0; k < order.size(); k++) {
            int idx = order.get(k);
            MediaItem m = c.getMediaItemAt(idx);
            Song s = Remote.song(m);
            RowAdapter.Row r = new RowAdapter.Row();
            r.type = RowAdapter.SONG;
            r.song = s;
            r.t1 = s != null ? s.title : String.valueOf(m.mediaMetadata.title);
            r.t2 = s != null ? s.artist : "";
            r.t3 = s != null ? Library.time(s.duration) : "";
            r.current = idx == cur;
            if (r.current) curRow = k;
            r.click = v -> { MediaController mc = remote.get(); if (mc != null) { mc.seekTo(idx, 0); mc.play(); } };
            if (s != null) {
                r.menu = anchor -> {
                    PopupMenu pm = new PopupMenu(this, anchor);
                    pm.getMenu().add(0, 1, 0, "Remove from queue");
                    pm.getMenu().add(0, 2, 0, "Play next");
                    pm.getMenu().add(0, 3, 0, "Add to playlist…");
                    pm.getMenu().add(0, 4, 0, "Edit tags");
                    pm.getMenu().add(0, 5, 0, "Details");
                    pm.setOnMenuItemClickListener(it -> {
                        MediaController mc = remote.get();
                        if (mc == null) return true;
                        switch (it.getItemId()) {
                            case 1: mc.removeMediaItem(idx); break;
                            case 2: {
                                int target = mc.getCurrentMediaItemIndex() + (idx > mc.getCurrentMediaItemIndex() ? 1 : 0);
                                mc.moveMediaItem(idx, target);
                                break;
                            }
                            case 3: Actions.addToPlaylist(this, Collections.singletonList(s)); break;
                            case 4: Actions.editTags(this, Collections.singletonList(s)); break;
                            case 5: Actions.details(this, s); break;
                            default: return false;
                        }
                        return true;
                    });
                    pm.show();
                };
            }
            rows.add(r);
        }
        // Reordering only makes sense in queue order, not shuffled play order.
        queueAdapter.setDragStarter(sh ? null : queueDragger::startDrag);
        queueAdapter.set(rows);
        final int scrollTo = Math.max(0, curRow - 2);
        queueList.post(() -> ((LinearLayoutManager) queueList.getLayoutManager()).scrollToPositionWithOffset(scrollTo, 0));
    }

    private void saveQueueAsPlaylist() {
        MediaController c = remote.get();
        if (c == null || c.getMediaItemCount() == 0) return;
        List<Song> songs = new ArrayList<>();
        for (int i : order) {
            Song s = Remote.song(c.getMediaItemAt(i));
            if (s != null) songs.add(s);
        }
        Ui.input(this, "Save queue as playlist", "", "Save", name -> {
            Db db = LyreApp.get().db();
            long id = db.createPlaylist(name);
            List<String> paths = new ArrayList<>();
            for (Song s : songs) paths.add(s.path);
            db.addToPlaylist(id, paths);
            Ui.toast(this, "Saved " + Library.plural(paths.size(), "song"));
        });
    }

    private void clearQueue() {
        MediaController c = remote.get();
        if (c == null) return;
        Ui.confirm(this, "Clear the queue?", "Everything except the current track is removed.", "Clear", () -> {
            int cur = c.getCurrentMediaItemIndex();
            int n = c.getMediaItemCount();
            if (cur + 1 < n) c.removeMediaItems(cur + 1, n);
            if (cur > 0) c.removeMediaItems(0, cur);
        });
    }

    // ---------------------------------------------------------------- menus

    private void sleepMenu() {
        PlaybackService svc = PlaybackService.get();
        if (svc == null) return;
        String[] items = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1½ hours", "2 hours", "After this track"};
        int[] mins = {0, 15, 30, 45, 60, 90, 120};
        Ui.choose(this, "Sleep timer", items, -1, w -> {
            if (w == items.length - 1) svc.sleepAtEndOfTrack();
            else svc.sleepIn(mins[w]);
            updateStatus();
        });
    }

    private void moreMenu(View anchor) {
        MediaController c = remote.get();
        Song s = c == null ? null : Remote.song(c.getCurrentMediaItem());
        if (s == null) return;
        Actions.song(this, anchor, s, null, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Theme or accent changed in Settings while this screen was open.
        if (themeVersion != Theme.version) recreate();
    }
}
