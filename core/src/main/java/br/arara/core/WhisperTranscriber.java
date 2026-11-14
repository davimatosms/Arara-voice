package br.arara.core;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Whisper (whisper.cpp via whisper-jni), sempre em português. Baixa o modelo escolhido nas configurações
 * na primeira vez e troca de modelo quando a configuração muda.
 * shortcut: só CPU (o whisper-jni 1.7.1 não tem build com Vulkan); Settings.accel é ignorado por enquanto.
 */
final class WhisperTranscriber implements Transcriber, AutoCloseable {
    // Orienta o estilo da saída: português do Brasil, com pontuação e acentos.
    private static final String PROMPT =
            "Olá! Tudo bem? Este é um ditado em português do Brasil, com vírgulas, pontos e acentuação corretos.";
    // Commit fixo do repositório de modelos, para os hashes abaixo não mudarem.
    private static final String MODELS_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/";

    // O padrão do whisper-jni (0) deixa o whisper.cpp quase sem paralelismo: medido 4,2 s contra 2,8 s com 6 threads
    // (base, 15 s de áudio, Ryzen 5 5500). Metade dos processadores lógicos ~ núcleos físicos; 12 threads só ganhou 4%.
    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    private final Path modelsDir;
    private final Supplier<Settings.Model> model;
    private WhisperJNI whisper;
    private WhisperContext context;
    private Settings.Model loaded;

    WhisperTranscriber(Path modelsDir, Supplier<Settings.Model> model) {
        this.modelsDir = modelsDir;
        this.model = model;
    }

    private static String fileName(Settings.Model m) {
        return switch (m) {
            case LEVE -> "ggml-base.bin";
            case EQUILIBRADO -> "ggml-small.bin";
            case PRECISO -> "ggml-large-v3-turbo-q5_0.bin";
        };
    }

    private static String sha256(Settings.Model m) {
        return switch (m) {
            case LEVE -> "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe";
            case EQUILIBRADO -> "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b";
            case PRECISO -> "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
        };
    }

    /** Baixa (se faltar) e carrega o modelo das configurações. progress recebe de 0.0 a 1.0 durante o download. */
    synchronized void prepare(DoubleConsumer progress) throws IOException {
        Settings.Model m = model.get();
        if (m == loaded) return;
        Path file = modelsDir.resolve(fileName(m));
        if (!Files.exists(file)) download(URI.create(MODELS_URL + fileName(m)), sha256(m), file, progress);
        if (whisper == null) {
            WhisperJNI.loadLibrary();
            WhisperJNI.setLibraryLogger(null); // sem log do whisper.cpp no console
            whisper = new WhisperJNI();
        }
        WhisperContext next = whisper.init(file);
        if (context != null) context.close();
        context = next;
        loaded = m;
    }

    @Override
    public synchronized String transcribe(short[] pcm16k) {
        try {
            prepare(p -> {}); // a UI deveria ter chamado antes; aqui só garante
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // whisper.cpp recusa áudio com menos de 1 s: completa com silêncio.
        float[] samples = new float[Math.max(pcm16k.length, Vad.RATE + Vad.RATE / 10)];
        for (int i = 0; i < pcm16k.length; i++) samples[i] = pcm16k[i] / 32768f;

        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.nThreads = THREADS;
        // Encoder só do tamanho do áudio (1500 = 30 s; 1 posição = 320 amostras), com folga. Medido com 9,5 s de fala:
        // turbo 16,5 s -> 5,0 s e base 1,0 s -> 0,35 s, mesmo texto.
        // shortcut: whisper.cpp avisa que pode perder precisão; voltar ao padrão (0) se aparecer erro em frase curta.
        params.audioCtx = Math.min(1500, samples.length / 320 + 64);
        params.language = "pt";
        params.initialPrompt = PROMPT;
        params.printProgress = false;
        params.printTimestamps = false;
        params.suppressNonSpeechTokens = true;
        int result = whisper.full(context, params, samples, samples.length);
        if (result != 0) throw new IllegalStateException("whisper.cpp falhou com código " + result);

        StringBuilder text = new StringBuilder();
        for (int i = 0, n = whisper.fullNSegments(context); i < n; i++) text.append(whisper.fullGetSegmentText(context, i));
        return text.toString().strip();
    }

    @Override
    public synchronized void close() {
        if (context != null) context.close();
        context = null;
        loaded = null;
    }

    /** Baixa para .part, confere o SHA-256 e só então renomeia: download interrompido ou adulterado não vira modelo. */
    static void download(URI url, String sha256, Path target, DoubleConsumer progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download do modelo interrompido.", e);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Falha ao baixar o modelo (HTTP " + response.statusCode() + ").");
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 20];
                long done = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    done += n;
                    if (total > 0) progress.accept((double) done / total);
                }
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException("O modelo baixado veio corrompido. Tente de novo.");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
