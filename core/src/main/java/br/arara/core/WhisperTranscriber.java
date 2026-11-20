package br.arara.core;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperContextParams;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
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
    // (base, 15 s de áudio, Ryzen 5 5500). Com o turbo os processadores lógicos ajudam: 10 s de áudio em 7,4 s com 6
    // threads, 5,3 s com 10 e 5,2 s com 12. Sobram 2 para o microfone e o resto do sistema.
    static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

    // A prévia divide a CPU com o modelo escolhido: poucas threads, para atrasar pouco o trecho dele.
    static final int PREVIEW_THREADS = 2;

    private final Path modelsDir;
    private final Supplier<Settings.Model> model;
    private final int threads;
    private WhisperJNI whisper;
    private WhisperContext context;
    private Settings.Model loaded;
    private Supplier<Boolean> gpu = () -> false;
    private Path gpuMarker;
    private boolean gpuProved; // a primeira transcrição com GPU desta sessão deu certo

    /** Liga a GPU, com o marcador de travamento em marker (ver {@link Gpu}). */
    WhisperTranscriber gpu(Supplier<Boolean> enabled, Path marker) {
        this.gpu = enabled;
        this.gpuMarker = marker;
        return this;
    }

    private boolean useGpu() {
        return gpuMarker != null && gpu.get();
    }

    private void mark(boolean on) {
        try {
            if (on) Files.writeString(gpuMarker, "1");
            else Files.deleteIfExists(gpuMarker);
        } catch (IOException ignored) {
            // sem o marcador só perde a proteção
        }
    }

    WhisperTranscriber(Path modelsDir, Supplier<Settings.Model> model) {
        this(modelsDir, model, THREADS);
    }

    WhisperTranscriber(Path modelsDir, Supplier<Settings.Model> model, int threads) {
        this.modelsDir = modelsDir;
        this.model = model;
        this.threads = threads;
    }

    /**
     * Prévia do texto ao vivo com o modelo LEVE num segundo contexto do Whisper. Carrega na primeira prévia; se o
     * arquivo falta, baixa ali mesmo (na thread da prévia, que pula as rodadas enquanto isso) e só então mostra.
     * Devolve null (sem prévia) quando o modelo escolhido já é o LEVE, sem espaço em disco ou com o download falhando.
     */
    static final class Preview implements Transcriber, AutoCloseable {
        private static final long MIN_FREE = 1L << 30; // o modelo leve tem 148 MB: deixa folga para o resto do disco
        private final WhisperTranscriber light;
        private final Path modelsDir;
        private final Supplier<Settings.Model> chosen;
        private long retryAt; // depois de falhar o download, espera antes de tentar de novo

        Preview(Path modelsDir, Supplier<Settings.Model> chosen) {
            // Se o usuário escolhe o LEVE no meio do download da prévia, o modelo "some" (null): o download para e
            // deixa o .part para o transcritor principal continuar, sem dois escrevendo no mesmo arquivo.
            this.light = new WhisperTranscriber(modelsDir, () -> chosen.get() == Settings.Model.LEVE ? null : Settings.Model.LEVE,
                    PREVIEW_THREADS);
            this.modelsDir = modelsDir;
            this.chosen = chosen;
        }

        @Override
        public String transcribe(short[] pcm16k) {
            return transcribe(pcm16k, "");
        }

        @Override
        public synchronized String transcribe(short[] pcm16k, String previous) {
            boolean wasLoaded = light.loaded != null;
            if (!ready() || !wasLoaded) return null; // acabou de carregar: só a partir da próxima rodada (esta já está velha)
            return light.transcribe(pcm16k, previous);
        }

        @Override
        public synchronized void load() {
            ready();
        }

        /** Comando de voz com o modelo leve; null quando o escolhido já é o Leve ou o modelo não carregou. */
        @Override
        public synchronized String transcribeCommand(short[] pcm16k) {
            return ready() ? light.transcribeCommand(pcm16k) : null; // comando não envelhece: usa logo depois de carregar
        }

        /** Modelo leve carregado (carrega, e baixa se faltar, na primeira vez); false se não deve ou não deu. */
        private boolean ready() {
            if (chosen.get() == Settings.Model.LEVE) return false; // o próprio modelo escolhido já é rápido
            if (light.loaded != null) return true;
            if (System.nanoTime() < retryAt) return false;
            boolean have = Files.exists(modelsDir.resolve(fileName(Settings.Model.LEVE)));
            if (!have && modelsDir.toFile().getUsableSpace() < MIN_FREE) return false;
            try {
                light.prepare((done, total) -> {});
                return true;
            } catch (IOException | RuntimeException e) {
                retryAt = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                return false;
            }
        }

        @Override
        public synchronized void close() {
            light.close();
        }
    }

    private static String fileName(Settings.Model m) {
        return switch (m) {
            case LEVE -> "ggml-base.bin";
            case EQUILIBRADO -> "ggml-small.bin";
            case PRECISO -> "ggml-large-v3-turbo-q8_0.bin";
        };
    }

    // PRECISO era o q5_0 até a v0.4.5: quem já o tem segue nele até o q8_0 baixar e ser conferido.
    static final String OLD_PRECISO = "ggml-large-v3-turbo-q5_0.bin";
    static final String UPGRADE_NOTICE = "Baixando a versão mais precisa do modelo Preciso…";

    /** Baixador e executor trocáveis nos testes. */
    interface Downloader {
        boolean get(URI url, String sha256, Path target, AraraCore.DownloadProgress progress, BooleanSupplier cancelled)
                throws IOException;
    }

    Downloader downloader = WhisperTranscriber::download;
    java.util.concurrent.Executor background = r -> {
        Thread t = new Thread(r, "arara-modelo");
        t.setDaemon(true);
        t.start();
    };
    private Path loadedFile;
    private boolean upgrading;

    /** Arquivo a carregar: o PRECISO novo se existe; senão o antigo, se existe; senão o novo (a baixar). */
    Path fileFor(Settings.Model m) {
        Path file = modelsDir.resolve(fileName(m));
        Path old = modelsDir.resolve(OLD_PRECISO);
        return m == Settings.Model.PRECISO && !Files.exists(file) && Files.exists(old) ? old : file;
    }

    /** Baixa o q8_0 em segundo plano; falhou ou foi cancelado, o q5_0 segue valendo (o .part continua depois). */
    private void upgradeInBackground() {
        if (upgrading) return;
        upgrading = true;
        System.err.println(UPGRADE_NOTICE);
        Settings.Model m = Settings.Model.PRECISO;
        background.execute(() -> {
            try {
                downloader.get(URI.create(MODELS_URL + fileName(m)), sha256(m), modelsDir.resolve(fileName(m)),
                        (done, total) -> {}, () -> model.get() != m);
            } catch (IOException | RuntimeException e) {
                // segue no q5_0; tenta de novo na próxima abertura
            } finally {
                synchronized (this) {
                    upgrading = false;
                }
            }
        });
    }

    private static String sha256(Settings.Model m) {
        return switch (m) {
            case LEVE -> "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe";
            case EQUILIBRADO -> "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b";
            case PRECISO -> "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1";
        };
    }

    /** Baixa (se faltar) e carrega o modelo das configurações. progress recebe os bytes durante o download. */
    synchronized void prepare(AraraCore.DownloadProgress progress) throws IOException {
        for (;;) {
            Settings.Model m = model.get();
            Path file = fileFor(m);
            if (m == loaded && file.equals(loadedFile)) return;
            // Trocou de modelo no meio do download: o .part fica para continuar depois e o novo modelo é baixado já.
            if (!Files.exists(file)
                    && !downloader.get(URI.create(MODELS_URL + fileName(m)), sha256(m), file, progress, () -> model.get() != m)) {
                continue;
            }
            if (whisper == null) {
                loadNative();
                WhisperJNI.setLibraryLogger(null); // sem log do whisper.cpp no console
                whisper = new WhisperJNI();
            }
            WhisperContextParams cp = new WhisperContextParams();
            cp.useGPU = useGpu();
            if (cp.useGPU && !gpuProved) mark(true);
            WhisperContext next = whisper.init(file, cp);
            // O whisper-jni devolve null (sem exceção) quando o whisper.cpp não consegue carregar: falta de memória, arquivo ruim.
            if (next == null) {
                throw new IllegalStateException("o whisper.cpp não carregou " + file.getFileName() + " (" + Files.size(file)
                        + " bytes; memória livre da máquina pode ser pouca)");
            }
            if (context != null) context.close();
            context = next;
            loaded = m;
            loadedFile = file;
            if (m == Settings.Model.PRECISO) {
                if (file.getFileName().toString().equals(OLD_PRECISO)) upgradeInBackground();
                else Files.deleteIfExists(modelsDir.resolve(OLD_PRECISO)); // q8_0 verificado e carregado: libera ~550 MB
            }
            return;
        }
    }

    /**
     * Carrega o whisper-jni. No Windows o whisper-jni usa a versão "fina" (que depende de whisper.dll/ggml*.dll soltas)
     * quando acha um whisper.dll no PATH, e o ggml do usuário aborta o processo (#83). Aqui extraímos sempre a versão
     * "full" (tudo embutido) e apontamos a libdir do whisper-jni para ela, sem olhar o PATH.
     */
    static synchronized void loadNative() throws IOException {
        preloadVcomp();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && System.getProperty("os.arch", "").contains("64") && System.getProperty(LIBDIR) == null) {
            try (InputStream in = WhisperJNI.class.getResourceAsStream("/win-amd64/whisper-jni_full.dll")) {
                if (in != null) {
                    Path dir = Files.createTempDirectory("arara-whisper-");
                    Path dll = dir.resolve("whisper-jni.dll");
                    Files.copy(in, dll);
                    dll.toFile().deleteOnExit();
                    dir.toFile().deleteOnExit();
                    System.setProperty(LIBDIR, dir.toString());
                }
            }
        }
        WhisperJNI.loadLibrary();
    }

    private static final String LIBDIR = "io.github.givimad.whisperjni.libdir";

    /** Windows: carrega o vcomp140.dll empacotado em runtime/bin; sem ele o whisper-jni dá UnsatisfiedLinkError num PC limpo. */
    private static void preloadVcomp() {
        Path dll = Path.of(System.getProperty("java.home"), "bin", "vcomp140.dll");
        if (Files.exists(dll)) System.load(dll.toString());
    }

    @Override
    public String transcribe(short[] pcm16k) {
        return transcribe(pcm16k, "");
    }

    @Override
    public void load() {
        try {
            prepare((done, total) -> {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String transcribe(short[] pcm16k, String previous) {
        // O prompt do Whisper aceita ~224 tokens; o fim do texto anterior basta para a continuidade.
        String tail = previous.length() > 300 ? previous.substring(previous.length() - 300) : previous;
        return run(pcm16k, p -> p.initialPrompt = tail.isBlank() ? PROMPT : PROMPT + " " + tail.strip());
    }

    /**
     * Vocabulário dos comandos no prompt, sem pontuação e com "Arara" por último. Sem prompt, o Whisper leve escrevia
     * "Há nada" e "Concela"; com pontos ("Arara. Pronto.") ou em outra ordem, ele continuava a lista e repetia a palavra
     * por até 5 s. Medido com voz real (e2e "bancada", 8 trechos x 6 recortes): "Arara Pronto Aprovado Cancela"
     * repetia em 17 de 48; esta ordem, em 1 de 48, com 48 reconhecidos e 0,37 s em média.
     */
    static final String COMMAND_PROMPT = "Aprovado Cancela Pronto Arara";
    /** O whisper.cpp exige 1 s; o trecho de comando vai com 1,5 s. */
    static final int COMMAND_SAMPLES = Vad.RATE * 3 / 2;

    /**
     * Comando de voz: completado com o ruído de fundo (não com silêncio digital, que fazia o Whisper alucinar e repetir),
     * um segmento só, sem contexto e sem a segunda tentativa com temperatura maior (que multiplicava o tempo).
     */
    @Override
    public String transcribeCommand(short[] pcm16k) {
        return transcribeCommand(pcm16k, p -> {});
    }

    /** Com ajustes extras por cima dos de comando (o e2e "bancada" compara alternativas). */
    synchronized String transcribeCommand(short[] pcm16k, java.util.function.Consumer<WhisperFullParams> extra) {
        return run(padWithBackground(pcm16k), p -> {
            p.initialPrompt = COMMAND_PROMPT;
            p.singleSegment = true;
            p.noContext = true;
            p.temperatureInc = 0;
            extra.accept(p);
        });
    }

    /** Completa até {@link #COMMAND_SAMPLES} repetindo o primeiro 0,1 s do trecho (a folga antes da fala: só fundo). */
    static short[] padWithBackground(short[] pcm) {
        int background = Math.min(pcm.length, Vad.RATE / 10);
        if (pcm.length >= COMMAND_SAMPLES || background == 0) return pcm;
        short[] out = java.util.Arrays.copyOf(pcm, COMMAND_SAMPLES);
        for (int i = pcm.length; i < out.length; i++) out[i] = pcm[(i - pcm.length) % background];
        return out;
    }

    private String run(short[] pcm16k, java.util.function.Consumer<WhisperFullParams> tune) {
        try {
            prepare((done, total) -> {}); // a UI deveria ter chamado antes; aqui só garante
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // whisper.cpp recusa áudio com menos de 1 s: completa com silêncio.
        float[] samples = new float[Math.max(pcm16k.length, Vad.RATE + Vad.RATE / 10)];
        for (int i = 0; i < pcm16k.length; i++) samples[i] = pcm16k[i] / 32768f;

        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.nThreads = threads;
        // Encoder só do tamanho do áudio (1500 = 30 s; 1 posição = 320 amostras), com folga. Medido com 9,5 s de fala:
        // turbo 16,5 s -> 5,0 s e base 1,0 s -> 0,35 s, mesmo texto.
        // shortcut: whisper.cpp avisa que pode perder precisão; voltar ao padrão (0) se aparecer erro em frase curta.
        params.audioCtx = Math.min(1500, samples.length / 320 + 64);
        params.language = "pt";
        params.printProgress = false;
        params.printTimestamps = false;
        params.suppressNonSpeechTokens = true;
        tune.accept(params);
        boolean testing = useGpu() && !gpuProved;
        if (testing) mark(true);
        int result = whisper.full(context, params, samples, samples.length);
        if (testing) {
            gpuProved = true;
            mark(false);
        }
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
        loadedFile = null;
    }

    /**
     * Baixa para .part, confere o SHA-256 e só então renomeia: download interrompido ou adulterado não vira modelo.
     * Se já existe um .part (a internet caiu), pede só o resto (Range) e continua de onde parou.
     *
     * @return false se {@code cancelled} pediu para parar (o .part fica para continuar depois)
     */
    /** SHA-256 diferente do esperado: o arquivo já foi apagado. */
    static final class ChecksumMismatch extends IOException {
        ChecksumMismatch() {
            super("O arquivo baixado veio corrompido e foi apagado. Tente de novo.");
        }
    }

    static boolean download(URI url, String sha256, Path target, AraraCore.DownloadProgress progress,
            BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long have = Files.exists(part) ? Files.size(part) : 0;
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(url);
        if (have > 0) request.header("Range", "bytes=" + have + "-");
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrompido.", e);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = response.body()) {
            int status = response.statusCode();
            if (status == 416) { // .part maior que o arquivo: recomeça do zero
                Files.deleteIfExists(part);
                return download(url, sha256, target, progress, cancelled);
            }
            boolean resume = status == 206
                    && response.headers().firstValue("content-range").orElse("").startsWith("bytes " + have + "-");
            if (status != 200 && !resume) {
                throw new IOException("Falha ao baixar (HTTP " + status + ").");
            }
            byte[] buf = new byte[1 << 20];
            if (resume) {
                try (InputStream old = Files.newInputStream(part)) {
                    for (int n; (n = old.read(buf)) > 0; ) digest.update(buf, 0, n);
                }
            } else {
                have = 0;
            }
            long length = response.headers().firstValueAsLong("content-length").orElse(-1);
            long total = length > 0 ? have + length : -1;
            try (OutputStream out = resume ? Files.newOutputStream(part, StandardOpenOption.APPEND) : Files.newOutputStream(part)) {
                long done = have;
                progress.update(done, total);
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancelled.getAsBoolean()) return false;
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    done += n;
                    progress.update(done, total);
                }
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            Files.deleteIfExists(part);
            throw new ChecksumMismatch();
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }
}
