/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

public class DupesTest {
    private static Song s(long id, String title, String artist, long durMs, String path, long size, int bitrate) {
        return new Song(id, title, artist, "Album", null, null, null, 1, 0, 0, 0, durMs, path, 0, 0, size, bitrate);
    }

    @Test
    public void sameSongDifferentFormatsGroupsAndPrefersLossless() {
        Song mp3 = s(1, "Hello, World!", "Artist", 200_000, "/m/a.mp3", 5_000_000, 320_000);
        Song flac = s(2, "hello world", "ARTIST", 201_500, "/m/b/a.flac", 30_000_000, 900_000);
        Song other = s(3, "Something else", "Artist", 200_000, "/m/c.mp3", 5_000_000, 320_000);
        List<List<Song>> g = Dupes.similar(Arrays.asList(mp3, flac, other));
        assertEquals(1, g.size());
        assertEquals(2, g.get(0).size());
        assertEquals(flac, g.get(0).get(0));
    }

    @Test
    public void liveVersionIsNotADuplicate() {
        Song studio = s(1, "Song", "Band", 200_000, "/a.mp3", 1, 0);
        Song live = s(2, "Song", "Band", 260_000, "/b.mp3", 1, 0);
        assertEquals(0, Dupes.similar(Arrays.asList(studio, live)).size());
    }

    @Test
    public void accentsIgnored() {
        assertEquals(Dupes.norm("Beyoncé"), Dupes.norm("beyonce"));
    }

    @Test
    public void identicalByContent() {
        Song a = s(1, "A", "X", 1, "/a.mp3", 4, 0);
        Song b = s(2, "B", "Y", 1, "/b.mp3", 4, 0);
        Song c = s(3, "C", "Z", 1, "/c.mp3", 4, 0);
        Dupes.Opener open = song -> new ByteArrayInputStream(song.id == 3 ? new byte[]{9, 9, 9, 9} : new byte[]{1, 2, 3, 4});
        List<List<Song>> g = Dupes.identical(Arrays.asList(a, b, c), open, null);
        assertEquals(1, g.size());
        assertEquals(2, g.get(0).size());
    }
}
