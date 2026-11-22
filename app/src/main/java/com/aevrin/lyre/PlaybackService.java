/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Plays music in the background. Media3 provides the notification, lock-screen,
 * Bluetooth and headset controls. Activities talk to it through a MediaController;
 * a few extras (sleep timer, normalization status) are plain methods on the
 * running instance, since everything is in one process.
 */
public class PlaybackService extends MediaSessionService {

    private static volatile PlaybackService instance;

    private ExoPlayer player;
    private MediaSession session;
    private Normalizer normalizer;
    private Prefs prefs;
    private Library library;
    private int audioSession;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Sleep timer
    private long sleepAt;            // elapsedRealtime, 0 = off
    private boolean sleepEndOfTrack;
    private final Runnable sleepNow = () -> {
        sleepAt = 0;
        if (player != null) player.pause();
    };
    // Skipping files that won't play; stops after a run of failures so a broken queue can't spin.
    private int consecutiveErrors;
    private boolean skippingFailed;

    // Play counting: a track counts as played after half its length or 4 minutes of listening.
    private String trackPath;
    private long trackDuration, listened, playingSince;
    private boolean counted;
    private final Runnable playTick = new Runnable() {
        @Override public void run() {
            checkPlay();
            if (playingSince > 0 && !counted) main.postDelayed(this, 5_000);
        }
    };
    private final Runnable saveTick = new Runnable() {
        @Override public void run() {
            saveQueue();
            if (player != null && player.isPlaying()) main.postDelayed(this, 15_000);
        }
    };

    @Nullable
    public static PlaybackService get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        LyreApp app = (LyreApp) getApplication();
        prefs = app.prefs();
        library = app.library();
        if (!library.isLoaded()) library.reload();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        AudioManager am = getSystemService(AudioManager.class);
        audioSession = am.generateAudioSessionId();
        player.setAudioSessionId(audioSession);
        normalizer = new Normalizer(this, player, app.db(), prefs, audioSession);
        openEffectSession();

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                updateWidgets();
                finishTrack(reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !skippingFailed);
                skippingFailed = false;
                onTrackChanged();
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                onPlaybackFailed(error);
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) {
                    consecutiveErrors = 0;
                    if (trackPath != null) ((LyreApp) getApplication()).db().clearFailure(trackPath);
                }
                long now = SystemClock.elapsedRealtime();
                if (playing && playingSince == 0) {
                    playingSince = now;
                    main.removeCallbacks(playTick);
                    main.postDelayed(playTick, 5_000);
                } else if (!playing && playingSince > 0) {
                    listened += now - playingSince;
                    playingSince = 0;
                    checkPlay();
                }
                main.removeCallbacks(saveTick);
                if (playing) main.postDelayed(saveTick, 15_000); else saveQueue();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                updateWidgets();
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                        && sleepEndOfTrack) {
                    sleepEndOfTrack = false;
                    player.setPauseAtEndOfMediaItems(false);
                    // Paused at the end of the old track; step onto the next so play resumes there.
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem();
                }
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean on) { saveModes(); }

            @Override
            public void onRepeatModeChanged(int mode) { saveModes(); }

            @Override
            public void onTimelineChanged(@NonNull androidx.media3.common.Timeline t, int reason) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue();
            }
        });

        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setBitmapLoader(new ArtLoader())
                .setCallback(new Callback())
                .build();

        restoreQueue();
        instance = this;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // Swiped away while paused: stop entirely. While playing: keep going.
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            saveQueue();
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        LyreApp.get().prefs().raw().edit().putBoolean("w_playing", false).apply();
        Widget.updateAll(this);
        if (playingSince > 0) {
            listened += SystemClock.elapsedRealtime() - playingSince;
            playingSince = 0;
        }
        checkPlay();
        main.removeCallbacksAndMessages(null);
        saveQueue();
        closeEffectSession();
        if (normalizer != null) normalizer.release();
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- items

    /** Builds a playable item for a song (URI, metadata and artwork reference). */
    static MediaItem item(Song s) {
        MediaMetadata md = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setAlbumArtist(s.albumArtist)
                .setGenre(s.genre)
                .setTrackNumber(s.track > 0 ? s.track : null)
                .setDiscNumber(s.disc > 0 ? s.disc : null)
                .setRecordingYear(s.year > 0 ? s.year : null)
                .setDurationMs(s.duration)
                .setArtworkUri(Uri.parse(ArtLoader.SCHEME + "://" + s.id))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build();
        return new MediaItem.Builder()
                .setMediaId(String.valueOf(s.id))
                .setUri(s.uri())
                .setMediaMetadata(md)
                .build();
    }

    /** Controllers only send ids; turn them into full items. Unknown ids are dropped. */
    private List<MediaItem> resolve(List<MediaItem> in) {
        List<MediaItem> out = new ArrayList<>(in.size());
        for (MediaItem m : in) {
            if (m.localConfiguration != null) { out.add(m); continue; }
            try {
                Song s = library.byId(Long.parseLong(m.mediaId));
                if (s != null) out.add(item(s));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    @Nullable
    private Song currentSong() {
        if (player == null) return null;
        MediaItem m = player.getCurrentMediaItem();
        if (m == null) return null;
        try { return library.byId(Long.parseLong(m.mediaId)); } catch (NumberFormatException e) { return null; }
    }

    /** Records the failure, tells the user, and moves on to the next track. */
    private void onPlaybackFailed(androidx.media3.common.PlaybackException error) {
        Song s = currentSong();
        String why = error.getErrorCodeName();
        if (s != null) ((LyreApp) getApplication()).db().addFailure(s.path, why);
        consecutiveErrors++;
        String name = s != null ? s.title : "This track";
        boolean canSkip = player.hasNextMediaItem() && consecutiveErrors < Math.min(10, player.getMediaItemCount());
        android.widget.Toast.makeText(this, canSkip ? "Can't play " + name + ", skipping"
                : "Can't play " + name, android.widget.Toast.LENGTH_SHORT).show();
        if (canSkip) {
            skippingFailed = true;   // not a user skip; don't count it
            player.seekToNextMediaItem();
            player.prepare();
            player.play();
        } else {
            consecutiveErrors = 0;
        }
    }

    private long listenedMs() {
        return listened + (playingSince > 0 ? SystemClock.elapsedRealtime() - playingSince : 0);
    }

    private void checkPlay() {
        if (counted || trackPath == null) return;
        long threshold = trackDuration > 0 ? Math.min(trackDuration / 2, 240_000) : 30_000;
        if (listenedMs() >= threshold) {
            counted = true;
            ((LyreApp) getApplication()).db().addPlay(trackPath, System.currentTimeMillis());
        }
    }

    /** Wraps up the track that's ending: count the play, or a skip if you moved on early. */
    private void finishTrack(boolean userSkipped) {
        checkPlay();
        if (!counted && trackPath != null && userSkipped && listenedMs() < 30_000) {
            ((LyreApp) getApplication()).db().addSkip(trackPath);
        }
        Song s = currentSong();
        trackPath = s == null ? null : s.path;
        trackDuration = s == null ? 0 : s.duration;
        listened = 0;
        counted = false;
        playingSince = player != null && player.isPlaying() ? SystemClock.elapsedRealtime() : 0;
        main.removeCallbacks(playTick);
        if (playingSince > 0) main.postDelayed(playTick, 5_000);
    }

    private void onTrackChanged() {
        Song s = currentSong();
        normalizer.apply(s);
        int next = player.getNextMediaItemIndex();
        if (next != C.INDEX_UNSET) {
            try {
                normalizer.prefetch(library.byId(Long.parseLong(player.getMediaItemAt(next).mediaId)));
            } catch (RuntimeException ignored) { }
        }
        saveQueue();
    }

    private final class Callback implements MediaSession.Callback {
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession s,
                @NonNull MediaSession.ControllerInfo c, @NonNull List<MediaItem> items) {
            return Futures.immediateFuture(resolve(items));
        }

        @SuppressWarnings("deprecation")
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c) {
            List<MediaItem> items = savedItems();
            int idx = Math.max(0, Math.min(prefs.savedIndex(), Math.max(0, items.size() - 1)));
            return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(
                    items, idx, items.isEmpty() ? C.TIME_UNSET : prefs.savedPosition()));
        }
    }

    // ---------------------------------------------------------------- persistence

    private void saveQueue() {
        if (player == null) return;
        int n = player.getMediaItemCount();
        if (n == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < 5000; i++) {
            if (i > 0) sb.append(',');
            sb.append(player.getMediaItemAt(i).mediaId);
        }
        prefs.saveQueue(sb.toString(), player.getCurrentMediaItemIndex(), Math.max(0, player.getCurrentPosition()));
    }

    private void saveModes() {
        if (player != null) prefs.saveModes(player.getShuffleModeEnabled(), player.getRepeatMode());
    }

    private List<MediaItem> savedItems() {
        List<MediaItem> out = new ArrayList<>();
        String ids = prefs.savedQueue();
        if (ids.isEmpty()) return out;
        for (String id : ids.split(",")) {
            try {
                Song s = library.byId(Long.parseLong(id));
                if (s != null) out.add(item(s));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Puts the last queue back (paused) so the app opens where you left off. */
    private void restoreQueue() {
        player.setShuffleModeEnabled(prefs.savedShuffle());
        player.setRepeatMode(prefs.savedRepeat());
        if (library.isLoaded()) {
            doRestore();
        } else {
            library.addListener(new Library.Listener() {
                @Override public void onLibraryChanged() {
                    library.removeListener(this);
                    if (player != null && player.getMediaItemCount() == 0) doRestore();
                }
            });
        }
    }

    private void doRestore() {
        List<MediaItem> items = savedItems();
        if (items.isEmpty()) return;
        int idx = Math.max(0, Math.min(prefs.savedIndex(), items.size() - 1));
        player.setMediaItems(items, idx, prefs.savedPosition());
        player.prepare();
        finishTrack(false);
        onTrackChanged();
        updateWidgets();
    }

    // ---------------------------------------------------------------- widgets

    private final java.util.concurrent.ExecutorService widgetIo = Executors.newSingleThreadExecutor();
    private long widgetArtId = -1;

    /** Pushes the current track and play state to any home-screen widgets. */
    public void updateWidgets() {
        if (player == null) return;
        final Song s = currentSong();
        final boolean playing = player.getPlayWhenReady();
        final long id = s == null ? -2 : s.id;
        final boolean artChanged = id != widgetArtId;
        widgetArtId = id;
        widgetIo.execute(() -> {
            Bitmap art = null;
            if (artChanged && s != null) {
                try { art = getContentResolver().loadThumbnail(s.uri(), new Size(320, 320), null); }
                catch (Exception ignored) { }
            }
            Widget.save(this, s == null ? null : s.title, s == null ? "" : s.artist, playing, art, artChanged);
        });
    }

    // ---------------------------------------------------------------- extras for the UI

    public Normalizer.Info normInfo() { return normalizer.info(); }
    public boolean canBoost() { return normalizer.canBoost(); }
    public void reapplyGain() { normalizer.reapply(); }
    public void applyEq() { normalizer.applyEq(); }
    public void measureAll() { normalizer.measureAll(library.songs()); }
    public void cancelMeasureAll() { normalizer.cancelBulk(); }
    public boolean measuring() { return normalizer.bulkRunning(); }
    public int measureDone() { return normalizer.bulkDone(); }
    public int measureTotal() { return normalizer.bulkTotal(); }
    public int measureFailed() { return normalizer.bulkFailed(); }
    public String measureCurrent() { return normalizer.bulkCurrent(); }
    public int audioSessionId() { return audioSession; }

    /** Pauses after this many minutes (0 cancels). */
    public void sleepIn(int minutes) {
        main.removeCallbacks(sleepNow);
        cancelEndOfTrack();
        if (minutes <= 0) { sleepAt = 0; return; }
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
        main.postDelayed(sleepNow, minutes * 60_000L);
    }

    /** Pauses when the current track finishes. */
    public void sleepAtEndOfTrack() {
        main.removeCallbacks(sleepNow);
        sleepAt = 0;
        sleepEndOfTrack = true;
        player.setPauseAtEndOfMediaItems(true);
    }

    private void cancelEndOfTrack() {
        if (sleepEndOfTrack) {
            sleepEndOfTrack = false;
            player.setPauseAtEndOfMediaItems(false);
        }
    }

    public void cancelSleep() {
        main.removeCallbacks(sleepNow);
        sleepAt = 0;
        cancelEndOfTrack();
    }

    /** Milliseconds until the sleep timer fires, -1 for end of track, 0 for off. */
    public long sleepRemaining() {
        if (sleepEndOfTrack) return -1;
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    // Lets system or third-party equalizers attach to our audio.
    private void openEffectSession() {
        Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSession)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        sendBroadcast(i);
    }

    private void closeEffectSession() {
        Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSession)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(i);
    }

    // ---------------------------------------------------------------- artwork

    /** Notification/lock-screen art from MediaStore thumbnails ("lyreart://<song id>"). */
    private final class ArtLoader implements BitmapLoader {
        static final String SCHEME = "lyreart";
        private final ListeningExecutorService exec =
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        private final DataSourceBitmapLoader fallback = new DataSourceBitmapLoader(PlaybackService.this);

        @Override
        public boolean supportsMimeType(@NonNull String mimeType) { return fallback.supportsMimeType(mimeType); }

        @NonNull
        @Override
        public ListenableFuture<Bitmap> decodeBitmap(@NonNull byte[] data) { return fallback.decodeBitmap(data); }

        @NonNull
        @Override
        public ListenableFuture<Bitmap> loadBitmap(@NonNull Uri uri) {
            if (!SCHEME.equals(uri.getScheme())) return fallback.loadBitmap(uri);
            return exec.submit(() -> {
                long id = Long.parseLong(uri.getHost());
                Song s = library.byId(id);
                Uri src = s != null ? s.uri()
                        : android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                return getContentResolver().loadThumbnail(src, new Size(512, 512), null);
            });
        }
    }
}
