/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LoudnessTest {
    /** BS.1770 reference: a 997 Hz sine peaking at -20 dBFS in both channels reads -20 LUFS. */
    @Test
    public void sineReadsAsExpected() {
        int rate = 48000;
        Loudness.Meter m = new Loudness.Meter(rate, 2);
        float amp = (float) Math.pow(10, -20 / 20.0);
        float[] buf = new float[rate * 2];
        for (int sec = 0; sec < 5; sec++) {
            for (int i = 0; i < rate; i++) {
                float v = (float) (amp * Math.sin(2 * Math.PI * 997 * (sec * rate + i) / rate));
                buf[2 * i] = v;
                buf[2 * i + 1] = v;
            }
            m.add(buf, buf.length);
        }
        assertEquals(-20.0, m.integrated(), 0.1);
    }

    @Test
    public void silenceIsNegativeInfinity() {
        Loudness.Meter m = new Loudness.Meter(44100, 2);
        m.add(new float[88200], 88200);
        assertEquals(Double.NEGATIVE_INFINITY, m.integrated(), 0);
    }

    @Test
    public void otherSampleRateMatches() {
        int rate = 44100;
        Loudness.Meter m = new Loudness.Meter(rate, 1);
        float amp = (float) Math.pow(10, -20 / 20.0);
        float[] buf = new float[rate * 5];
        for (int i = 0; i < buf.length; i++) buf[i] = (float) (amp * Math.sin(2 * Math.PI * 997 * i / rate));
        m.add(buf, buf.length);
        // Mono: one channel's energy only, 3 dB lower than the stereo case.
        assertEquals(-23.0, m.integrated(), 0.1);
    }
}
