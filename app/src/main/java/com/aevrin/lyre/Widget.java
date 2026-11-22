/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.KeyEvent;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Home-screen widgets. The playback service saves what's playing (and a small
 * copy of the cover) and pushes it here; the buttons send media-button events
 * through Media3's receiver, which also resumes playback when Lyre isn't running.
 */
final class Widget {
    private Widget() { }

    private static final String ART_FILE = "widget_art.png";

    /** Remembers the current track for widgets added or redrawn later, then redraws them. */
    static void save(Context ctx, String title, String artist, boolean playing, Bitmap art, boolean artChanged) {
        SharedPreferences sp = LyreApp.get().prefs().raw();
        sp.edit().putString("w_title", title).putString("w_artist", artist).putBoolean("w_playing", playing).apply();
        if (artChanged) {
            File f = new File(ctx.getFilesDir(), ART_FILE);
            if (art == null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    art.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) {
                    android.util.Log.w("Lyre", "Widget art not saved", e);
                }
            }
        }
        updateAll(ctx, artChanged ? art : null, !artChanged);
    }

    static void updateAll(Context ctx) { updateAll(ctx, null, true); }

    private static void updateAll(Context ctx, Bitmap art, boolean loadArt) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        if (m == null) return;
        int[] small = m.getAppWidgetIds(new ComponentName(ctx, WidgetSmall.class));
        int[] large = m.getAppWidgetIds(new ComponentName(ctx, WidgetLarge.class));
        if (small.length == 0 && large.length == 0) return;
        if (loadArt) {
            File f = new File(ctx.getFilesDir(), ART_FILE);
            if (f.exists()) art = BitmapFactory.decodeFile(f.getPath());
        }
        if (small.length > 0) m.updateAppWidget(small, views(ctx, R.layout.widget_small, art));
        if (large.length > 0) m.updateAppWidget(large, views(ctx, R.layout.widget_large, art));
    }

    private static RemoteViews views(Context ctx, int layout, Bitmap art) {
        SharedPreferences sp = LyreApp.get().prefs().raw();
        String title = sp.getString("w_title", null);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);
        rv.setTextViewText(R.id.w_title, title == null ? "Lyre" : title);
        rv.setTextViewText(R.id.w_artist, title == null ? "Tap play to resume" : sp.getString("w_artist", ""));
        rv.setImageViewResource(R.id.w_play, sp.getBoolean("w_playing", false) ? R.drawable.widget_pause : R.drawable.widget_play);
        if (art != null) rv.setImageViewBitmap(R.id.w_art, art);
        else rv.setImageViewResource(R.id.w_art, R.drawable.widget_note);
        rv.setOnClickPendingIntent(R.id.w_play, button(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        rv.setOnClickPendingIntent(R.id.w_next, button(ctx, KeyEvent.KEYCODE_MEDIA_NEXT));
        rv.setOnClickPendingIntent(R.id.w_prev, button(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        Intent open = new Intent(ctx, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.w_art, pi);
        rv.setOnClickPendingIntent(R.id.w_title, pi);
        rv.setOnClickPendingIntent(R.id.w_artist, pi);
        return rv;
    }

    private static PendingIntent button(Context ctx, int keyCode) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(new ComponentName(ctx, androidx.media3.session.MediaButtonReceiver.class))
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        return PendingIntent.getBroadcast(ctx, keyCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Shared provider behaviour: draw from the saved state. */
    abstract static class Provider extends AppWidgetProvider {
        @Override
        public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) { updateAll(ctx); }
    }
}
