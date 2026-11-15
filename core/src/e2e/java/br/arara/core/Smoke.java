package br.arara.core;

import io.github.givimad.whisperjni.WhisperJNI;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Teste de fumaça sem interface, para o CI (feito para o runner do macOS; roda em qualquer sistema):
 * carrega o whisper-jni, baixa o modelo leve, transcreve, e liga o AraraCore com o atalho global do JNativeHook.
 * Falha (código 1) só se o Whisper não carregar ou não transcrever; o atalho só é relatado, porque no macOS
 * ele depende da permissão de Acessibilidade, que o runner pode não ter.
 *
 * ./gradlew :core:smoke --args="<pasta de trabalho> [fala.wav]"
 */
public final class Smoke {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(600_000);
                System.out.println("SMOKE TIMEOUT (10 min)");
                Runtime.getRuntime().halt(2);
            } catch (InterruptedException ignored) {
                // terminou antes
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        System.out.printf("os=%s %s arch=%s java=%s%n", System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), System.getProperty("java.version"));

        // 1. Whisper: modelo leve (base, 148 MB, SHA-256 conferido) e uma transcrição
        WhisperTranscriber whisper = new WhisperTranscriber(dir.resolve("models"), () -> Settings.Model.LEVE);
        long t = System.nanoTime();
        whisper.prepare((done, total) -> {});
        System.out.printf("whisper: modelo pronto em %.1f s%n", (System.nanoTime() - t) / 1e9);
        short[] pcm = args.length > 1 && new File(args[1]).exists() ? wav(new File(args[1])) : new short[Vad.RATE * 2];
        t = System.nanoTime();
        String text = whisper.transcribe(pcm);
        System.out.printf(Locale.ROOT, "whisper: %.1f s de áudio transcritos em %.2f s: \"%s\"%n",
                pcm.length / (double) Vad.RATE, (System.nanoTime() - t) / 1e9, text);

        // 1b. Prévia do texto ao vivo: segundo contexto do modelo leve, rodando junto com o modelo escolhido
        try (WhisperTranscriber.Preview preview = new WhisperTranscriber.Preview(dir.resolve("models"), () -> Settings.Model.PRECISO)) {
            preview.transcribe(pcm); // a primeira só carrega
            CompletableFuture<String> main = CompletableFuture.supplyAsync(() -> whisper.transcribe(pcm));
            t = System.nanoTime();
            String draft = preview.transcribe(pcm);
            double took = (System.nanoTime() - t) / 1e9;
            main.join();
            if (draft == null) throw new IllegalStateException("a prévia não carregou o modelo leve");
            System.out.printf(Locale.ROOT, "whisper prévia (%d threads, junto com o principal): %.2f s: \"%s\"%n",
                    WhisperTranscriber.PREVIEW_THREADS, took, draft);
        }
        whisper.close();

        // 2. Backend do whisper.cpp (Metal no Apple Silicon?): o log da biblioteca diz ao carregar o modelo
        StringBuilder log = new StringBuilder();
        WhisperJNI.setLibraryLogger(log::append);
        new WhisperJNI().init(dir.resolve("models/ggml-base.bin")).close();
        WhisperJNI.setLibraryLogger(null);
        log.toString().lines().filter(l -> l.toLowerCase(Locale.ROOT).matches(".*(metal|gpu|backend|blas|cpu).*"))
                .limit(15).forEach(l -> System.out.println("whisper.cpp: " + l.strip()));

        // 3. AraraCore com o JNativeHook (no macOS, sem Acessibilidade, deve abrir sem atalho em vez de falhar)
        try (AraraCore core = AraraCore.start(dir.resolve("dados"), p -> "")) {
            System.out.println("atalho global registrado: " + core.retryHotkey());
        } catch (Throwable e) {
            System.out.println("atalho global: " + e);
            if (AraraCore.MAC) throw e; // no macOS isso não pode impedir o app de abrir
        }
        System.out.println("SMOKE OK");
        System.exit(0); // o JNativeHook deixa threads vivas
    }

    /** WAV qualquer convertido para 16 kHz, mono, 16 bits. */
    private static short[] wav(File file) throws Exception {
        AudioFormat target = new AudioFormat(Vad.RATE, 16, 1, true, false);
        try (AudioInputStream in = AudioSystem.getAudioInputStream(target, AudioSystem.getAudioInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            ByteBuffer b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            short[] pcm = new short[b.remaining() / 2];
            b.asShortBuffer().get(pcm);
            return pcm;
        }
    }
}
