/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmartTest {
    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;

    private static Song song(String title, String path, long durMin, long addedDaysAgo, int year) {
        return new Song(1, title, "Artist", "Album", null, "Rock", null, 1, year, 1, 1,
                durMin * 60_000, path, (NOW - addedDaysAgo * DAY) / 1000, 0, 0);
    }

    @Test
    public void lengthAndPlaysAll() {
        Song longOne = song("Long", "/m/long.flac", 12, 10, 2000);
        Song shortOne = song("Short", "/m/short.flac", 3, 10, 2000);
        Map<String, Smart.Stat> stats = new HashMap<>();
        Smart.Stat st = new Smart.Stat();
        st.plays = 6;
        stats.put(longOne.path, st);
        Smart s = new Smart();
        s.rules.add(new Smart.Rule(Smart.LENGTH, Smart.GT, "10"));
        s.rules.add(new Smart.Rule(Smart.PLAYS, Smart.GE, "5"));
        List<Song> r = s.evaluate(Arrays.asList(longOne, shortOne), stats, NOW);
        assertEquals(1, r.size());
        assertEquals("Long", r.get(0).title);
    }

    @Test
    public void addedOlderThan() {
        Smart s = new Smart();
        s.rules.add(new Smart.Rule(Smart.ADDED, Smart.OLDER, "365"));
        assertTrue(s.matches(song("a", "/a", 3, 400, 0), null, NOW));
        assertFalse(s.matches(song("b", "/b", 3, 30, 0), null, NOW));
    }

    @Test
    public void neverPlayedCountsAsOld() {
        Smart s = new Smart();
        s.rules.add(new Smart.Rule(Smart.LAST_PLAYED, Smart.OLDER, "30"));
        assertTrue(s.matches(song("a", "/a", 3, 1, 0), null, NOW));
        Smart w = new Smart();
        w.rules.add(new Smart.Rule(Smart.LAST_PLAYED, Smart.WITHIN, "30"));
        assertFalse(w.matches(song("a", "/a", 3, 1, 0), null, NOW));
    }

    @Test
    public void anyMatchesEither() {
        Smart s = new Smart();
        s.matchAll = false;
        s.rules.add(new Smart.Rule(Smart.YEAR, Smart.LT, "1980"));
        s.rules.add(new Smart.Rule(Smart.TITLE, Smart.CONTAINS, "live"));
        assertTrue(s.matches(song("Old", "/a", 3, 1, 1975), null, NOW));
        assertTrue(s.matches(song("Song (Live)", "/b", 3, 1, 2010), null, NOW));
        assertFalse(s.matches(song("New", "/c", 3, 1, 2010), null, NOW));
    }

    @Test
    public void limitAndSort() {
        Song a = song("A", "/a", 3, 1, 0), b = song("B", "/b", 3, 2, 0), c = song("C", "/c", 3, 3, 0);
        Smart s = new Smart();
        s.sort = Smart.SORT_ADDED_OLD;
        s.limit = 2;
        List<Song> r = s.evaluate(Arrays.asList(a, b, c), new HashMap<>(), NOW);
        assertEquals(Arrays.asList(c, b), r);
    }

    @Test
    public void jsonRoundTrip() {
        Smart s = Smart.preset(5);
        s.matchAll = false;
        s.limit = 25;
        Smart t = Smart.fromJson("x", s.toJson());
        assertFalse(t.matchAll);
        assertEquals(25, t.limit);
        assertEquals(Smart.SORT_LONGEST, t.sort);
        assertEquals(1, t.rules.size());
        assertEquals(Smart.LENGTH, t.rules.get(0).field);
        assertEquals("10", t.rules.get(0).value);
    }
}
