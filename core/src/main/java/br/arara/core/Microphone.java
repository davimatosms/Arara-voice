package br.arara.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Captura 16 kHz, mono, PCM 16-bit, em blocos de 512 amostras (~31 por segundo). */
final class Microphone implements DefaultDictationEngine.AudioSource {
    private static final AudioFormat FORMAT = new AudioFormat(Vad.RATE, 16, 1, true, false);
    private static final DataLine.Info INFO = new DataLine.Info(TargetDataLine.class, FORMAT);
    private static final int CHUNK = 512;

    private final Supplier<String> mixerName;
    private volatile TargetDataLine line;

    /** @param mixerName nome do mixer escolhido, ou null para o padrão do sistema */
    Microphone(Supplier<String> mixerName) {
        this.mixerName = mixerName;
    }

    /** Nomes dos mixers que conseguem gravar no formato do Whisper. */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(INFO)) names.add(info.getName());
        }
        return names;
    }

    @Override
    public void start(Consumer<short[]> onChunk) throws LineUnavailableException {
        TargetDataLine l = open(mixerName.get());
        l.open(FORMAT, CHUNK * 2 * 8);
        l.start();
        line = l;
        Thread t = new Thread(() -> {
            byte[] buf = new byte[CHUNK * 2];
            while (l.isOpen()) {
                int n = l.read(buf, 0, buf.length);
                if (n <= 0) continue;
                short[] samples = new short[n / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((buf[2 * i] & 0xff) | (buf[2 * i + 1] << 8));
                }
                onChunk.accept(samples);
            }
        }, "arara-microfone");
        t.setDaemon(true);
        t.start();
    }

    private static TargetDataLine open(String name) throws LineUnavailableException {
        if (name != null) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(info);
                if (info.getName().equals(name) && m.isLineSupported(INFO)) return (TargetDataLine) m.getLine(INFO);
            }
        }
        // Microfone escolhido sumiu (ex.: headset desligado): usa o padrão do sistema.
        return (TargetDataLine) AudioSystem.getLine(INFO);
    }

    @Override
    public void stop() {
        TargetDataLine l = line;
        line = null;
        if (l != null) {
            l.stop();
            l.close();
        }
    }
}
