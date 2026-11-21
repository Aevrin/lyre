/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.ComponentName;
import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An activity's connection to {@link PlaybackService}. Connect in onStart,
 * release in onStop. Queue helpers send song ids only; the service fills in
 * the rest.
 */
final class Remote {
    interface OnReady { void ready(MediaController c); }

    private final Context ctx;
    private final Player.Listener listener;
    private ListenableFuture<MediaController> future;
    private MediaController controller;

    Remote(Context ctx, Player.Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void connect(OnReady ready) {
        if (future != null) return;
        SessionToken token = new SessionToken(ctx, new ComponentName(ctx, PlaybackService.class));
        future = new MediaController.Builder(ctx, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                if (listener != null) controller.addListener(listener);
                if (ready != null) ready.ready(controller);
            } catch (Exception e) {
                android.util.Log.w("Lyre", "Couldn't connect to playback", e);
            }
        }, MoreExecutors.directExecutor());
    }

    void release() {
        if (future != null) MediaController.releaseFuture(future);
        future = null;
        controller = null;
    }

    MediaController get() { return controller; }

    // ---------------------------------------------------------------- queue helpers

    static List<MediaItem> items(List<Song> songs) {
        List<MediaItem> out = new ArrayList<>(songs.size());
        for (Song s : songs) out.add(new MediaItem.Builder().setMediaId(String.valueOf(s.id)).build());
        return out;
    }

    /** Replaces the queue and starts playing at index (or shuffled from a random track). */
    void play(List<Song> songs, int index, boolean shuffle) {
        MediaController c = controller;
        if (c == null || songs.isEmpty()) return;
        List<Song> list = songs;
        if (shuffle) {
            list = new ArrayList<>(songs);
            Collections.shuffle(list);
            index = 0;
            c.setShuffleModeEnabled(false);
        }
        c.setMediaItems(items(list), Math.max(0, Math.min(index, list.size() - 1)), C.TIME_UNSET);
        c.prepare();
        c.play();
    }

    /** Inserts after the current track. */
    void playNext(List<Song> songs) {
        MediaController c = controller;
        if (c == null || songs.isEmpty()) return;
        if (c.getMediaItemCount() == 0) { play(songs, 0, false); return; }
        c.addMediaItems(c.getCurrentMediaItemIndex() + 1, items(songs));
    }

    void enqueue(List<Song> songs) {
        MediaController c = controller;
        if (c == null || songs.isEmpty()) return;
        if (c.getMediaItemCount() == 0) { play(songs, 0, false); return; }
        c.addMediaItems(items(songs));
    }

    static Song song(MediaItem m) {
        if (m == null) return null;
        try { return LyreApp.get().library().byId(Long.parseLong(m.mediaId)); }
        catch (NumberFormatException e) { return null; }
    }

    static void togglePlay(Player p) {
        if (p == null) return;
        if (p.isPlaying()) { p.pause(); return; }
        if (p.getPlaybackState() == Player.STATE_IDLE) p.prepare();
        if (p.getPlaybackState() == Player.STATE_ENDED) p.seekToDefaultPosition();
        p.play();
    }
}
