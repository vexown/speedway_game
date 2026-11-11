package com.broadside.speedway.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.Random;

/**
 * Real-time synthesiser streaming to a libGDX AudioDevice on its own thread (no sound files):
 * methanol single-cylinder engines, crowd ambience, crash/thump noise bursts, tape snap, beeps.
 */
public class Synth implements Disposable, Runnable {
    private static final int RATE = 44100, BLOCK = 512;
    private AudioDevice device;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean muted;
    private final Random rnd = new Random();

    /** Engine voice parameters written by the game thread, smoothed per sample by the audio thread. */
    public static final class Voice {
        volatile float freqT, cutoffT, gainT, panT;
        float freq = 50, cutoff = 800, gain, pan, ph1, ph2, ph3, lp1, lp2;
    }

    public final Voice[] voices = new Voice[4];

    private static final class Burst {
        float t, dur, vol, cutoff, q; boolean band; float low, bandv;
        Burst(float dur, float vol, float cutoff, float q, boolean band) {
            this.dur = dur; this.vol = vol; this.cutoff = cutoff; this.q = q; this.band = band;
        }
    }

    private static final class Beep { float t, dur, freq, ph; Beep(float f, float d) { freq = f; dur = d; } }

    private final ArrayList<Burst> pending = new ArrayList<>();
    private final ArrayList<Burst> bursts = new ArrayList<>();
    private final ArrayList<Beep> beeps = new ArrayList<>();
    private final ArrayList<Beep> pendingBeeps = new ArrayList<>();
    private volatile float crowdTarget = 0.05f;
    private float crowd = 0.05f, crowdHold;
    private float brown, crowdLow, crowdBand;

    public Synth() {
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
    }

    public void start() {
        if (running) return;
        try {
            device = Gdx.audio.newAudioDevice(RATE, false);
        } catch (Throwable t) {
            Gdx.app.error("Synth", "No audio device: " + t.getMessage());
            return;
        }
        running = true;
        thread = new Thread(this, "synth");
        thread.setDaemon(true);
        thread.start();
    }

    public void setMuted(boolean m) { muted = m; }
    public boolean isMuted() { return muted; }

    /** rpm 0..1, throttle 0..1, volume 0..1, pan -1..1 */
    public void setEngine(int id, boolean isPlayer, float rpm, float throttle, float volume, float pan) {
        Voice v = voices[id];
        v.freqT = 38 + rpm * 165;
        v.cutoffT = 500 + rpm * 1800 + throttle * 1500;
        float base = isPlayer ? 0.32f : 0.22f;
        v.gainT = volume * base * (0.45f + 0.55f * throttle);
        v.panT = Math.max(-1, Math.min(1, pan));
    }

    public void silenceEngines() { for (Voice v : voices) v.gainT = 0; }

    private void addBurst(Burst b) { synchronized (pending) { pending.add(b); } }

    public void crash(float strength) {
        addBurst(new Burst(0.7f, 0.9f * strength, 300, 1, false));
        addBurst(new Burst(0.25f, 0.4f * strength, 2500, 0.5f, true));
    }

    public void thump(float strength) { addBurst(new Burst(0.25f, Math.min(1, strength), 160, 1, false)); }

    public void tapes() {
        addBurst(new Burst(0.12f, 0.5f, 3000, 4, true));
        cheer(0.8f);
    }

    public void cheer(float amount) {
        crowdTarget = 0.05f + 0.25f * amount;
        crowdHold = 0.3f;
    }

    public void beep(float freq, float dur) { synchronized (pending) { pendingBeeps.add(new Beep(freq, dur)); } }

    @Override
    public void run() {
        float[] buf = new float[BLOCK * 2];
        float dt = 1f / RATE;
        while (running) {
            synchronized (pending) {
                bursts.addAll(pending); pending.clear();
                beeps.addAll(pendingBeeps); pendingBeeps.clear();
            }
            for (int n = 0; n < BLOCK; n++) {
                float l = 0, r = 0;
                // Engines: saw + square + triangle sub, tanh drive, 2-pole lowpass
                for (Voice v : voices) {
                    v.freq += (v.freqT - v.freq) * 0.0015f;
                    v.cutoff += (v.cutoffT - v.cutoff) * 0.001f;
                    v.gain += (v.gainT - v.gain) * 0.0008f;
                    v.pan += (v.panT - v.pan) * 0.001f;
                    if (v.gain < 1e-4f && v.gainT < 1e-4f) continue;
                    v.ph1 = (v.ph1 + v.freq * dt) % 1f;
                    v.ph2 = (v.ph2 + v.freq * 0.502f * dt) % 1f;
                    v.ph3 = (v.ph3 + v.freq * 0.25f * dt) % 1f;
                    float saw = v.ph1 * 2 - 1;
                    float sq = v.ph2 < 0.5f ? 1 : -1;
                    float tri = 1 - 4 * Math.abs(v.ph3 - 0.5f);
                    float s = (float) Math.tanh((saw + sq + tri) * 0.35f * 3);
                    float a = Math.min(1, 2 * (float) Math.PI * v.cutoff * dt);
                    v.lp1 += a * (s - v.lp1);
                    v.lp2 += a * (v.lp1 - v.lp2);
                    float out = v.lp2 * v.gain;
                    l += out * (1 - Math.max(0, v.pan));
                    r += out * (1 + Math.min(0, v.pan));
                }
                // Crowd: brown noise through a band-pass
                float w = rnd.nextFloat() * 2 - 1;
                brown = (brown + 0.02f * w) / 1.02f;
                float noise = brown * 3.5f + w * 0.15f;
                if (crowdHold > 0) { crowdHold -= dt; crowd += (crowdTarget - crowd) * 0.0005f; }
                else { crowd += (0.05f - crowd) * 0.00002f; }
                float f = 2 * (float) Math.sin(Math.PI * 900 / RATE);
                crowdLow += f * crowdBand;
                float high = noise - crowdLow - 1.6f * crowdBand;
                crowdBand += f * high;
                l += crowdBand * crowd; r += crowdBand * crowd;
                // Noise bursts
                for (int i = bursts.size() - 1; i >= 0; i--) {
                    Burst b = bursts.get(i);
                    b.t += dt;
                    if (b.t >= b.dur) { bursts.remove(i); continue; }
                    float env = b.vol * (float) Math.exp(-6.9 * b.t / b.dur);
                    float fb = 2 * (float) Math.sin(Math.PI * Math.min(b.cutoff, RATE / 6f) / RATE);
                    float bn = rnd.nextFloat() * 2 - 1;
                    b.low += fb * b.bandv;
                    float hi = bn - b.low - (1 / Math.max(0.3f, b.q)) * b.bandv;
                    b.bandv += fb * hi;
                    float o = (b.band ? b.bandv : b.low) * env;
                    l += o; r += o;
                }
                for (int i = beeps.size() - 1; i >= 0; i--) {
                    Beep bp = beeps.get(i);
                    bp.t += dt;
                    if (bp.t >= bp.dur) { beeps.remove(i); continue; }
                    bp.ph += bp.freq * dt;
                    float o = (float) Math.sin(bp.ph * 2 * Math.PI) * 0.15f * (float) Math.exp(-6.9 * bp.t / bp.dur);
                    l += o; r += o;
                }
                float m = muted ? 0 : 0.6f;
                buf[n * 2] = (float) Math.tanh(l * m);
                buf[n * 2 + 1] = (float) Math.tanh(r * m);
            }
            device.writeSamples(buf, 0, buf.length);
        }
    }

    @Override
    public void dispose() {
        running = false;
        if (thread != null) try { thread.join(300); } catch (InterruptedException ignored) {}
        if (device != null) device.dispose();
    }
}
