/*
 * Lyre — a vibe-coded Android music player.
 * Copyright (C) 2026 Aevrin. Licensed under the GNU GPL v3 or later; see LICENSE.
 */
package com.aevrin.lyre;


import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures a track's integrated loudness (EBU R128 / ITU-R BS.1770, the same
 * method ReplayGain 2.0 uses) by decoding it with Android's own codecs.
 * Pure math lives in {@link Meter} so it can be unit-tested on the JVM.
 */
public final class Loudness {
    private Loudness() { }

    /** Returns {lufs, peak} or null if the file can't be decoded. Blocking; call off the main thread. */
    public static float[] measure(Context ctx, Song song, java.util.concurrent.atomic.AtomicBoolean cancel) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, song.uri(), null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            Meter meter = new Meter(rate, channels);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            float[] scratch = new float[0];
            int idle = 0;   // loop turns with no input or output accepted

            while (!outputDone) {
                if (cancel != null && cancel.get()) return null;
                boolean progressed = false;
                // Feed every input buffer that's free, without waiting.
                while (!inputDone) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in < 0) break;
                    progressed = true;
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                // Drain every decoded buffer that's ready; wait briefly only when nothing happened.
                while (!outputDone) {
                    int out = codec.dequeueOutputBuffer(info, progressed ? 0 : 5_000);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    progressed = true;
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        int r = of.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : rate;
                        int ch = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        if (r != rate || ch != channels) {
                            rate = r;
                            channels = ch;
                            meter = new Meter(rate, channels);
                        }
                    } else if (out >= 0) {
                        ByteBuffer ob = codec.getOutputBuffer(out);
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.nativeOrder());
                            int samples;
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                samples = info.size / 4;
                                if (scratch.length < samples) scratch = new float[samples];
                                ob.asFloatBuffer().get(scratch, 0, samples);
                            } else {
                                samples = info.size / 2;
                                if (scratch.length < samples) scratch = new float[samples];
                                java.nio.ShortBuffer sb = ob.asShortBuffer();
                                for (int i = 0; i < samples; i++) scratch[i] = sb.get(i) / 32768f;
                            }
                            meter.add(scratch, samples);
                        }
                        codec.releaseOutputBuffer(out, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    }
                }
                // A decoder that does nothing for ~10 s won't recover; give up on this file.
                if (progressed) idle = 0;
                else if (++idle > 2000) throw new IllegalStateException("decoder stalled");
            }
            double lufs = meter.integrated();
            if (Double.isInfinite(lufs) || Double.isNaN(lufs)) return null;
            return new float[]{(float) lufs, meter.peak()};
        } catch (Exception | Error e) {
            android.util.Log.w("Lyre", "Loudness: couldn't decode " + song.path + ": " + e);
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    /** BS.1770 K-weighted, gated loudness meter over interleaved float samples. */
    public static final class Meter {
        private final int channels;
        private final double[][] st;          // per channel: 4 state values per biquad x2
        private final double b0, b1, b2, a1, a2;  // stage 1: high shelf
        private final double c0, c1, c2, d1, d2;  // stage 2: high pass
        private final int blockLen;           // samples per channel in 100 ms
        private int inBlock;
        private double blockSum;
        private final List<Double> sub = new ArrayList<>(); // mean-square energy per 100 ms
        private float peak;

        public Meter(int rate, int channels) {
            this.channels = Math.max(1, channels);
            st = new double[this.channels][8];
            blockLen = Math.max(1, rate / 10);
            // Coefficients for any sample rate (as in libebur128).
            double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
            double k = Math.tan(Math.PI * f0 / rate);
            double vh = Math.pow(10.0, g / 20.0), vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1.0 + k / q + k * k;
            b0 = (vh + vb * k / q + k * k) / a0;
            b1 = 2.0 * (k * k - vh) / a0;
            b2 = (vh - vb * k / q + k * k) / a0;
            a1 = 2.0 * (k * k - 1.0) / a0;
            a2 = (1.0 - k / q + k * k) / a0;
            f0 = 38.13547087602444;
            q = 0.5003270373238773;
            k = Math.tan(Math.PI * f0 / rate);
            double n = 1.0 + k / q + k * k;
            c0 = 1.0; c1 = -2.0; c2 = 1.0;
            d1 = 2.0 * (k * k - 1.0) / n;
            d2 = (1.0 - k / q + k * k) / n;
        }

        public void add(float[] interleaved, int count) {
            int frames = count / channels;
            int i = 0;
            for (int f = 0; f < frames; f++) {
                double sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                    float x = interleaved[i++];
                    float ax = Math.abs(x);
                    if (ax > peak) peak = ax;
                    double[] s = st[ch];
                    // stage 1 (direct form I)
                    double y1 = b0 * x + b1 * s[0] + b2 * s[1] - a1 * s[2] - a2 * s[3];
                    s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y1;
                    // stage 2
                    double y2 = c0 * y1 + c1 * s[4] + c2 * s[5] - d1 * s[6] - d2 * s[7];
                    s[5] = s[4]; s[4] = y1; s[7] = s[6]; s[6] = y2;
                    sum += y2 * y2;
                }
                blockSum += sum;
                if (++inBlock == blockLen) {
                    sub.add(blockSum / blockLen);
                    blockSum = 0;
                    inBlock = 0;
                }
            }
        }

        public float peak() { return peak; }

        /** Integrated loudness in LUFS, or -infinity for silence. */
        public double integrated() {
            int n = sub.size() - 3; // 400 ms blocks overlapping by 75%
            if (n <= 0) {
                // Shorter than 400 ms: one block over what we have.
                double e = 0;
                for (double d : sub) e += d;
                return sub.isEmpty() ? Double.NEGATIVE_INFINITY : lufs(e / sub.size());
            }
            double[] blocks = new double[n];
            for (int i = 0; i < n; i++) {
                blocks[i] = (sub.get(i) + sub.get(i + 1) + sub.get(i + 2) + sub.get(i + 3)) / 4.0;
            }
            double absGate = energy(-70.0);
            double sum = 0;
            int cnt = 0;
            for (double b : blocks) if (b > absGate) { sum += b; cnt++; }
            if (cnt == 0) return Double.NEGATIVE_INFINITY;
            double relGate = energy(lufs(sum / cnt) - 10.0);
            double gate = Math.max(absGate, relGate);
            sum = 0;
            cnt = 0;
            for (double b : blocks) if (b > gate) { sum += b; cnt++; }
            return cnt == 0 ? Double.NEGATIVE_INFINITY : lufs(sum / cnt);
        }

        static double lufs(double energy) { return -0.691 + 10.0 * Math.log10(energy); }
        static double energy(double lufs) { return Math.pow(10.0, (lufs + 0.691) / 10.0); }
    }
}
