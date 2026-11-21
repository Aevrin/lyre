/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaylistIoTest {
    @Test
    public void relativizeSiblingFolders() {
        assertEquals("../Artist/Album/01 Song.flac",
                PlaylistIo.relativize("/storage/emulated/0/Music/Playlists",
                        "/storage/emulated/0/Music/Artist/Album/01 Song.flac"));
    }

    @Test
    public void relativizeSameFolder() {
        assertEquals("a.mp3", PlaylistIo.relativize("/storage/emulated/0/Music", "/storage/emulated/0/Music/a.mp3"));
    }

    @Test
    public void normalizeDotDot() {
        assertEquals("/storage/emulated/0/Music/A/b.mp3",
                PlaylistIo.normalize("/storage/emulated/0/Music/Playlists/../A/./b.mp3"));
    }

    @Test
    public void docIdToPath() {
        assertEquals("/storage/emulated/0/Music", PlaylistIo.pathOfDocId("primary:Music"));
        assertEquals("/storage/1234-ABCD/x", PlaylistIo.pathOfDocId("1234-ABCD:x"));
    }

    @Test
    public void parseDisc() {
        assertEquals(2, Library.parseLeadingInt("2/3"));
        assertEquals(0, Library.parseLeadingInt(null));
    }
}
