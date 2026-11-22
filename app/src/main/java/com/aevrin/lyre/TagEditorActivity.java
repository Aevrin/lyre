/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.jaudiotagger.tag.FieldKey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Edits tags for one song or several at once. With several, fields that differ
 * between the songs start empty and are left alone unless you type something.
 * Saving asks Android for permission to modify the files (one system dialog for
 * the whole batch), then rewrites them and has the media scanner re-read them.
 */
public class TagEditorActivity extends Activity {

    static final String EXTRA_IDS = "ids";
    private static final int REQ_WRITE = 1, REQ_ART = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Song> songs = new ArrayList<>();
    private final Map<FieldKey, String> initial = new LinkedHashMap<>();   // shown values
    private final Map<FieldKey, Boolean> mixed = new LinkedHashMap<>();
    private final Map<FieldKey, EditText> edits = new LinkedHashMap<>();
    private LinearLayout form;
    private ImageView artView;
    private TextView artNote;
    private ProgressBar busy;
    private Tags.ArtChange artChange;
    private Map<FieldKey, String> pendingChanges;
    private boolean saving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        long[] ids = getIntent().getLongArrayExtra(EXTRA_IDS);
        Library lib = LyreApp.get().library();
        if (ids != null) for (long id : ids) {
            Song s = lib.byId(id);
            if (s != null) songs.add(s);
        }
        if (songs.isEmpty()) { finish(); return; }
        setContentView(build());
        load();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.fitSystemBars(root);

        LinearLayout top = new LinearLayout(this);
        Ui.center(top);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        top.addView(Ui.icon(this, R.drawable.ic_close, "Cancel", v -> finish()));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(Ui.text(this, "Edit tags", 18, R.color.text_primary, true));
        tt.addView(Ui.text(this, songs.size() == 1 ? songs.get(0).fileName()
                : Library.plural(songs.size(), "song"), 13, R.color.text_muted, false));
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        busy = new ProgressBar(this);
        busy.setVisibility(View.GONE);
        top.addView(busy, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        top.addView(Ui.button(this, "Save", v -> save()));
        root.addView(top, Ui.matchWrap());

        ScrollView scroll = new ScrollView(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Cover art
        LinearLayout artRow = new LinearLayout(this);
        Ui.center(artRow);
        artView = new ImageView(this);
        artView.setClipToOutline(true);
        artView.setBackground(Ui.rounded(this, R.color.surface, 8));
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artRow.addView(artView, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        LinearLayout artButtons = new LinearLayout(this);
        artButtons.setOrientation(LinearLayout.VERTICAL);
        artButtons.setPadding(Ui.dp(this, 16), 0, 0, 0);
        artNote = Ui.text(this, "", 13, R.color.text_muted, false);
        artNote.setSingleLine(false);
        artButtons.addView(artNote);
        LinearLayout ab = new LinearLayout(this);
        ab.setPadding(0, Ui.dp(this, 8), 0, 0);
        ab.addView(Ui.button(this, "Change", v -> pickArt()));
        View gap = new View(this);
        ab.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        ab.addView(Ui.button(this, "Remove", v -> {
            artChange = Tags.ArtChange.remove();
            artView.setImageResource(R.drawable.ic_album);
            artNote.setText("Cover art will be removed");
        }));
        artButtons.addView(ab);
        artRow.addView(artButtons, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(artRow, Ui.matchWrap());
        return root;
    }

    /** Reads every file's tags off the main thread, then builds the fields. */
    private void load() {
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Map<FieldKey, String> first = null;
            Map<FieldKey, Boolean> diff = new LinkedHashMap<>();
            String error = null;
            for (Song s : songs) {
                Map<FieldKey, String> t;
                try {
                    t = Tags.read(s);
                } catch (Exception | Error e) {
                    error = s.fileName() + ": " + e.getMessage();
                    t = fromMediaStore(s);
                }
                if (first == null) {
                    first = t;
                    for (FieldKey k : Tags.KEYS) diff.put(k, false);
                } else {
                    for (FieldKey k : Tags.KEYS) {
                        if (!eq(first.get(k), t.get(k))) diff.put(k, true);
                    }
                }
            }
            byte[] art = songs.size() == 1 ? Tags.readArt(songs.get(0)) : null;
            final Map<FieldKey, String> f = first;
            final String err = error;
            main.post(() -> {
                if (isFinishing()) return;
                busy.setVisibility(View.GONE);
                for (FieldKey k : Tags.KEYS) {
                    boolean m = Boolean.TRUE.equals(diff.get(k));
                    mixed.put(k, m);
                    initial.put(k, m ? "" : f.get(k));
                }
                buildFields();
                if (art != null) {
                    Bitmap b = BitmapFactory.decodeByteArray(art, 0, art.length);
                    if (b != null) artView.setImageBitmap(b);
                    artNote.setText("Embedded cover");
                } else {
                    artView.setImageResource(R.drawable.ic_album);
                    artNote.setText(songs.size() == 1 ? "No embedded cover" : "Change sets the same cover on all songs");
                }
                if (err != null) Ui.toast(this, "Couldn't read tags from " + err);
            });
        });
    }

    private static Map<FieldKey, String> fromMediaStore(Song s) {
        Map<FieldKey, String> m = new LinkedHashMap<>();
        for (FieldKey k : Tags.KEYS) m.put(k, "");
        m.put(FieldKey.TITLE, s.title);
        m.put(FieldKey.ARTIST, s.artist);
        m.put(FieldKey.ALBUM, s.album);
        m.put(FieldKey.ALBUM_ARTIST, s.albumArtist);
        m.put(FieldKey.GENRE, s.genre);
        m.put(FieldKey.COMPOSER, s.composer);
        if (s.year > 0) m.put(FieldKey.YEAR, String.valueOf(s.year));
        if (s.track > 0) m.put(FieldKey.TRACK, String.valueOf(s.track));
        if (s.disc > 0) m.put(FieldKey.DISC_NO, String.valueOf(s.disc));
        return m;
    }

    private List<String> artists;

    private List<String> artistNames() {
        if (artists == null) artists = LyreApp.get().library().artistNames();
        return artists;
    }

    private void buildFields() {
        for (int i = 0; i < Tags.KEYS.length; i++) {
            FieldKey k = Tags.KEYS[i];
            TextView label = Ui.text(this, Tags.LABELS[i], 13, R.color.accent, true);
            label.setPadding(0, Ui.dp(this, 14), 0, 0);
            form.addView(label);
            boolean artistField = k == FieldKey.ARTIST || k == FieldKey.ALBUM_ARTIST;
            EditText e;
            if (artistField) {
                // Suggest artists already in the library once two characters are typed.
                AutoCompleteTextView ac = new AutoCompleteTextView(this);
                ac.setThreshold(2);
                ac.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, artistNames()));
                e = ac;
            } else {
                e = new EditText(this);
            }
            e.setText(initial.get(k));
            e.setTextColor(Ui.color(this, R.color.text_primary));
            boolean numeric = k == FieldKey.YEAR || k == FieldKey.TRACK || k == FieldKey.DISC_NO;
            if (numeric) {
                e.setInputType(InputType.TYPE_CLASS_NUMBER);
            } else if (k == FieldKey.COMMENT) {
                e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            } else {
                e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                e.setSingleLine(true);
            }
            if (Boolean.TRUE.equals(mixed.get(k))) e.setHint("Various — leave empty to keep each song's own");
            form.addView(e, Ui.matchWrap());
            edits.put(k, e);
        }
        if (songs.size() > 1) {
            TextView note = Ui.text(this, "Clearing a field that shows a shared value removes it from every song.",
                    12, R.color.text_muted, false);
            note.setSingleLine(false);
            note.setPadding(0, Ui.dp(this, 16), 0, 0);
            form.addView(note);
        }
    }

    // ---------------------------------------------------------------- art

    private void pickArt() {
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        startActivityForResult(i, REQ_ART);
    }

    private void onArtPicked(Uri uri) {
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                Bitmap src = BitmapFactory.decodeStream(in);
                if (src == null) return;
                // Keep covers reasonable: at most 1000px, JPEG.
                int max = Math.max(src.getWidth(), src.getHeight());
                Bitmap b = src;
                if (max > 1000) {
                    float sc = 1000f / max;
                    b = Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * sc), Math.round(src.getHeight() * sc), true);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                byte[] data = bos.toByteArray();
                final Bitmap shown = b;
                main.post(() -> {
                    artChange = Tags.ArtChange.set(data, "image/jpeg");
                    artView.setImageBitmap(shown);
                    artNote.setText("New cover (" + shown.getWidth() + "×" + shown.getHeight() + ")");
                });
            } catch (Exception e) {
                main.post(() -> Ui.toast(this, "Couldn't load that image"));
            }
        });
    }

    // ---------------------------------------------------------------- save

    private void save() {
        if (saving || edits.isEmpty()) return;
        Map<FieldKey, String> changes = new LinkedHashMap<>();
        for (FieldKey k : Tags.KEYS) {
            EditText e = edits.get(k);
            if (e == null) continue;
            String now = e.getText().toString().trim();
            String was = initial.get(k) == null ? "" : initial.get(k).trim();
            boolean isMixed = Boolean.TRUE.equals(mixed.get(k));
            if (isMixed && now.isEmpty()) continue;         // keep each song's own value
            if (!isMixed && now.equals(was)) continue;      // unchanged
            changes.put(k, now);
        }
        if (changes.isEmpty() && artChange == null) { finish(); return; }
        pendingChanges = changes;
        // Ask Android for write access to these files (one dialog for all of them).
        List<Uri> uris = new ArrayList<>();
        for (Song s : songs) uris.add(s.uri());
        try {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
            startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Ui.toast(this, "Couldn't ask for write access: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ART && res == RESULT_OK && data != null && data.getData() != null) {
            onArtPicked(data.getData());
        } else if (req == REQ_WRITE) {
            if (res == RESULT_OK) write();
            else Ui.toast(this, "Not saved: write access wasn't granted");
        }
    }

    private void write() {
        saving = true;
        busy.setVisibility(View.VISIBLE);
        final Map<FieldKey, String> changes = pendingChanges;
        final Tags.ArtChange art = artChange;
        io.execute(() -> {
            List<String> failed = new ArrayList<>();
            List<String> done = new ArrayList<>();
            for (Song s : songs) {
                try {
                    Tags.write(this, s, changes, art);
                    done.add(s.path);
                } catch (Exception | Error e) {
                    android.util.Log.w("Lyre", "Tag write failed for " + s.path, e);
                    failed.add(s.fileName() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
            Tags.rescan(this, done, () -> main.post(() -> {
                Art.forget();
                LyreApp.get().library().reload();
                PlaybackService svc = PlaybackService.get();
                if (svc != null) svc.reapplyGain();
            }));
            main.post(() -> {
                saving = false;
                busy.setVisibility(View.GONE);
                if (failed.isEmpty()) {
                    Ui.toast(this, "Saved " + Library.plural(done.size(), "song"));
                    finish();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String f : failed) sb.append(f).append('\n');
                    new AlertDialog.Builder(this)
                            .setTitle(done.isEmpty() ? "Nothing saved" : "Saved " + done.size() + ", " + failed.size() + " failed")
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("OK", (d, w) -> { if (!done.isEmpty()) finish(); })
                            .show();
                }
            });
        });
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

}
