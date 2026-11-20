package br.arara.core;

import com.github.kwhat.jnativehook.NativeHookException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sound.sampled.LineUnavailableException;

/** Ponto de entrada do backend para o app: liga armazenamento, microfone, motor e atalho global. */
public final class AraraCore implements AutoCloseable {
    /** Rodando no macOS (o resto do código assume Windows). */
    public static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
    /** Rodando no Linux. */
    public static final boolean LINUX = System.getProperty("os.name", "").startsWith("Linux");

    /** Sessão Wayland (o atalho global e o colar por teclas não funcionam lá); o ambiente vem por parâmetro para testar. */
    static boolean wayland(boolean linux, Function<String, String> env) {
        return linux && ("wayland".equals(env.apply("XDG_SESSION_TYPE")) || env.apply("WAYLAND_DISPLAY") != null);
    }

    /** Dispara o pedido de permissão do portal (GNOME Wayland); bloqueia até a resposta. true = permitido. */
    public static boolean requestPastePermission() {
        return LinuxPaste.grant(new LinuxPaste.SystemEnv());
    }

    /** Apaga a permissão guardada (portal-token) para o pedido aparecer de novo. */
    public static void resetPastePermission() {
        try {
            java.nio.file.Files.deleteIfExists(defaultDataDir().resolve("portal-token"));
        } catch (java.io.IOException ignored) {
            // token antigo continua valendo; o usuário tenta de novo
        }
    }

    public static boolean wayland() {
        return wayland(LINUX, System::getenv);
    }

    /** Diagnóstico do colar no Linux/Wayland: cole a saída num relatório. Deixe um editor de texto focado. */
    public static void diagnosticoColar(java.util.function.Consumer<String> log) throws Exception {
        log.accept("Wayland=" + wayland() + "  sessão=" + System.getenv("XDG_SESSION_TYPE")
                + "  desktop=" + System.getenv("XDG_CURRENT_DESKTOP"));
        LinuxPaste.Env env = new LinuxPaste.SystemEnv();
        for (String t : new String[] {"wtype", "ydotool", "wl-copy", "python3"}) log.accept(t + ": " + (env.has(t) ? "sim" : "nao"));
        log.accept("socket do ydotoold: " + env.ydotoolSocket());
        log.accept("token do portal salvo: " + !env.token().isEmpty());
        ClipboardPaster paster = new ClipboardPaster();
        log.accept("Foque um editor de texto: colando em 5 s...");
        Thread.sleep(5000);
        try {
            paster.paste("Teste de colagem do Arara. ");
            log.accept("estrategia usada: " + paster.lastStrategy);
        } catch (LinuxPaste.CopiedOnly e) {
            log.accept("NENHUMA estrategia funcionou: " + e.getMessage());
        }
        Thread.sleep(2000); // no X11 o clipboard morre com o processo: dá tempo do app de destino pedir o texto
    }

    private final HistoryStore history;
    private final DictionaryStore dictionary;
    private final SettingsStore settings;
    private final Transcriber transcriber;
    private final Transcriber preview; // null: sem prévia
    private final DefaultDictationEngine engine;
    private GlobalHotkey hotkey; // null no macOS enquanto o Arara não tem permissão de Acessibilidade

    private AraraCore(HistoryStore history, DictionaryStore dictionary, SettingsStore settings,
            Transcriber transcriber, Transcriber preview, DefaultDictationEngine engine, GlobalHotkey hotkey) {
        this.preview = preview;
        this.history = history;
        this.dictionary = dictionary;
        this.settings = settings;
        this.transcriber = transcriber;
        this.engine = engine;
        this.hotkey = hotkey;
    }

    /** %APPDATA%\Arara no Windows; ~/Library/Application Support/Arara no macOS; $XDG_DATA_HOME/Arara (ou ~/.local/share/Arara) no Linux. */
    public static Path defaultDataDir() {
        return dataDir(MAC, System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME"), System.getProperty("user.home"));
    }

    static Path dataDir(boolean mac, String appData, String xdgDataHome, String home) {
        if (mac) return Path.of(home, "Library", "Application Support", "Arara");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "Arara");
        boolean xdg = xdgDataHome != null && !xdgDataHome.isBlank();
        return xdg ? Path.of(xdgDataHome, "Arara") : Path.of(home, ".local", "share", "Arara");
    }

    /** Só lê o config.json (padrões se não existe), sem criar nada: o aviso da segunda cópia usa o tema escolhido. */
    public static Settings readSettings(Path dataDir) {
        return new JsonSettingsStore(dataDir.resolve("config.json")).load();
    }

    /** Nomes dos microfones para a tela de configurações (o valor vai em Settings.microphone). */
    public static List<String> microphones() {
        return Microphone.names();
    }

    /**
     * Abre o microfone só para medir o nível (teste da primeira execução): não grava nem transcreve.
     * Os blocos (~31 por segundo) chegam numa thread própria. Feche o retorno para soltar o microfone.
     *
     * @param microphone nome do mixer, ou null para o padrão do sistema
     */
    public static AutoCloseable listen(String microphone, Consumer<short[]> onChunk) throws LineUnavailableException {
        Microphone mic = new Microphone(() -> microphone);
        mic.start(onChunk);
        return mic::stop;
    }

    /** Nível de um bloco de áudio de 0.0 a 1.0, o mesmo do {@link DictationListener#onLevel}. */
    public static float level(short[] chunk) {
        return Vad.level(Vad.dbfs(chunk));
    }

    /**
     * Com o Whisper real, modelo em dataDir\models. Chame {@link #prepareModel} logo depois para baixar o
     * modelo com barra de progresso; senão o primeiro ditado espera o download.
     *
     * @throws IllegalStateException se não der para registrar o atalho global (no macOS, sem Acessibilidade,
     *         abre sem atalho: veja {@link #hotkeyActive})
     */
    public static AraraCore start(Path dataDir) {
        Path models = dataDir.resolve("models");
        return create(dataDir, s -> new WhisperTranscriber(models, () -> s.load().model())
                        .gpu(() -> Gpu.enabled(s.load()), dataDir.resolve(Gpu.MARKER)),
                s -> new WhisperTranscriber.Preview(models, () -> s.load().model()));
    }

    /** @throws IllegalStateException se não der para registrar o atalho global */
    public static AraraCore start(Path dataDir, Transcriber transcriber) {
        return create(dataDir, s -> transcriber, s -> null);
    }

    private static AraraCore create(Path dataDir, Function<SettingsStore, Transcriber> makeTranscriber,
            Function<SettingsStore, Transcriber> makePreview) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HistoryStore history = new JsonlHistoryStore(dataDir.resolve("historico.jsonl"));
        DictionaryStore dictionary = new JsonDictionaryStore(dataDir.resolve("dicionario.json"));
        SettingsStore settings = Startup.system().wrap(new JsonSettingsStore(dataDir.resolve("config.json")));
        String notice = Gpu.recover(dataDir, settings);
        Transcriber transcriber = makeTranscriber.apply(settings);
        Transcriber preview = makePreview.apply(settings);
        DefaultDictationEngine engine = new DefaultDictationEngine(history, dictionary, transcriber,
                new Microphone(() -> settings.load().microphone()), new ClipboardPaster(), preview);
        engine.setSoundSettings(settings);
        // Comandos de voz com o modelo leve: o da prévia, ou o principal quando o escolhido já é o Leve.
        VoiceCommandRecognizer recognizer = VoiceCommandRecognizer.whisper(
                () -> preview == null || settings.load().model() == Settings.Model.LEVE ? transcriber : preview);
        engine.setVoice(settings, recognizer);
        CommandListener commands = new CommandListener(engine, new Microphone(() -> settings.load().microphone()),
                recognizer, () -> settings.load().voiceWake());
        engine.addListener(commands);
        GlobalHotkey hotkey = null;
        try {
            hotkey = GlobalHotkey.register(engine, settings);
        } catch (NativeHookException e) {
            if (!MAC) { // no Mac falta Acessibilidade: segue sem atalho
                engine.close();
                throw new IllegalStateException("Não consegui registrar o atalho global.", e);
            }
        }
        AraraCore core = new AraraCore(history, dictionary, settings, transcriber, preview, engine, hotkey);
        core.notice = notice;
        core.commands = commands;
        commands.start();
        return core;
    }

    /** A GPU está valendo nestas configurações (Mac). */
    public static boolean gpuOn(Settings s) {
        return Gpu.enabled(s);
    }

    private String notice;
    private CommandListener commands; // comandos de voz ("Arara", "Aprovado", "Cancela")

    /** Aviso da partida (a GPU travou e foi desligada), entregue uma vez; null se não há. */
    public synchronized String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    /**
     * Atalho global funcionando? Só consulta: NÃO registra de novo. No macOS cada registro sem Acessibilidade reabre o
     * pedido do sistema (era o laço de pedidos), e um gancho criado antes da permissão não passa a valer: o usuário
     * liga o Arara em Ajustes e reabre o app.
     */
    public synchronized boolean hotkeyActive() {
        return hotkey != null;
    }

    /** Bytes baixados até agora e o total (-1 se o servidor não disser). Chamado na thread do download. */
    public interface DownloadProgress {
        void update(long done, long total);
    }

    /**
     * Baixa (na primeira vez) e carrega o modelo escolhido nas configurações. Bloqueia: chame fora da EDT.
     * Chame de novo depois de trocar o modelo; trocar no meio do download para este e baixa o novo.
     * Se a internet cair, lança IOException e a próxima chamada continua do ponto onde parou.
     */
    public void prepareModel(DownloadProgress progress) throws IOException {
        if (transcriber instanceof WhisperTranscriber w) w.prepare(progress);
    }

    public DictationEngine engine() {
        return engine;
    }

    public HistoryStore history() {
        return history;
    }

    public DictionaryStore dictionary() {
        return dictionary;
    }

    public SettingsStore settings() {
        return settings;
    }

    @Override
    public synchronized void close() {
        if (hotkey != null) hotkey.close();
        if (commands != null) commands.close();
        engine.close();
        if (transcriber instanceof WhisperTranscriber w) w.close(); // espera a transcrição em andamento
        if (preview instanceof WhisperTranscriber.Preview p) p.close();
    }
}
