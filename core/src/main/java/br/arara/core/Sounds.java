package br.arara.core;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Sons de começar e parar de ouvir, sintetizados aqui (sem arquivos de terceiros). Falha de áudio = silêncio. */
public final class Sounds {
    static final int RATE = 44100;
    private static final double ATTACK = 0.005, RELEASE = 0.04;

    private Sounds() {}

    /** Duração em ms do som com os valores dados. */
    public static int millis(Settings s) {
        double base = switch (s.sound()) {
            case ARARA -> 120;
            case PIXEL -> 120; // 2 notas de 60 ms
            case SUAVE -> 180; // 2 notas de 90 ms
        };
        return (int) Math.round(base * s.soundLength().factor);
    }

    /** PCM 16-bit mono 44,1 kHz; pico <= volume/100. */
    static short[] synth(Settings s, boolean start) {
        double f = s.soundLength().factor, amp = s.soundVolume() / 100.0;
        double[][] segs; // {f0, f1, segundos}
        boolean square = false, bird = false;
        switch (s.sound()) {
            case PIXEL -> {
                square = true;
                double c = 1046.5, g = 1568.0, d = 0.06 * f;
                segs = start ? new double[][] {{c, c, d}, {g, g, d}} : new double[][] {{g, g, d}, {c, c, d}};
            }
            case SUAVE -> {
                double d = 0.09 * f;
                segs = start ? new double[][] {{660, 660, d}, {990, 990, d}} : new double[][] {{990, 990, d}, {660, 660, d}};
            }
            default -> {
                bird = true;
                segs = new double[][] {start ? new double[] {1800, 3000, 0.12 * f} : new double[] {3000, 1800, 0.12 * f}};
            }
        }
        int total = 0;
        for (double[] g : segs) total += (int) (RATE * g[2]);
        short[] out = new short[total];
        int o = 0;
        for (double[] g : segs) {
            int n = (int) (RATE * g[2]);
            double ph = 0, dur = n / (double) RATE;
            for (int i = 0; i < n; i++) {
                double t = i / (double) RATE;
                ph += 2 * Math.PI * (g[0] + (g[1] - g[0]) * i / n) / RATE;
                double v = bird ? Math.sin(ph + 0.8 * Math.sin(2 * Math.PI * 38 * t))
                        : square ? (Math.sin(ph) > 0 ? 1 : -1) : Math.sin(ph);
                double env = Math.min(1, t / ATTACK) * Math.min(1, Math.max(0, (dur - t) / RELEASE));
                out[o++] = (short) Math.round(v * env * amp * 32767);
            }
        }
        return out;
    }

    /** Toca sem bloquear (thread daemon). Desligado ou sem saída de áudio: não faz nada. */
    public static void play(Settings s, boolean start) {
        if (!s.soundOn() || s.soundVolume() == 0) return;
        Thread t = new Thread(() -> {
            try {
                short[] pcm = synth(s, start);
                byte[] b = new byte[pcm.length * 2];
                for (int i = 0; i < pcm.length; i++) {
                    b[2 * i] = (byte) pcm[i];
                    b[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                try (SourceDataLine line = AudioSystem.getSourceDataLine(new AudioFormat(RATE, 16, 1, true, false))) {
                    line.open();
                    line.start();
                    line.write(b, 0, b.length);
                    line.drain();
                }
            } catch (Exception | LinkageError e) {
                // sem som: o ditado segue
            }
        }, "arara-som");
        t.setDaemon(true);
        t.start();
    }
}
