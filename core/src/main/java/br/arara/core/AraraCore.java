package br.arara.core;

import com.github.kwhat.jnativehook.NativeHookException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/** Ponto de entrada do backend para o app: liga armazenamento, microfone, motor e atalho global. */
public final class AraraCore implements AutoCloseable {
    private final HistoryStore history;
    private final DictionaryStore dictionary;
    private final SettingsStore settings;
    private final Transcriber transcriber;
    private final DefaultDictationEngine engine;
    private final GlobalHotkey hotkey;

    private AraraCore(HistoryStore history, DictionaryStore dictionary, SettingsStore settings,
            Transcriber transcriber, DefaultDictationEngine engine, GlobalHotkey hotkey) {
        this.history = history;
        this.dictionary = dictionary;
        this.settings = settings;
        this.transcriber = transcriber;
        this.engine = engine;
        this.hotkey = hotkey;
    }

    /** %APPDATA%\Arara */
    public static Path defaultDataDir() {
        return Path.of(System.getenv("APPDATA"), "Arara");
    }

    /** Nomes dos microfones para a tela de configurações (o valor vai em Settings.microphone). */
    public static List<String> microphones() {
        return Microphone.names();
    }

    /**
     * Com o Whisper real, modelo em dataDir\models. Chame {@link #prepareModel} logo depois para baixar o
     * modelo com barra de progresso; senão o primeiro ditado espera o download.
     *
     * @throws IllegalStateException se não der para registrar o atalho global
     */
    public static AraraCore start(Path dataDir) {
        return create(dataDir, s -> new WhisperTranscriber(dataDir.resolve("models"), () -> s.load().model()));
    }

    /** @throws IllegalStateException se não der para registrar o atalho global */
    public static AraraCore start(Path dataDir, Transcriber transcriber) {
        return create(dataDir, s -> transcriber);
    }

    private static AraraCore create(Path dataDir, Function<SettingsStore, Transcriber> makeTranscriber) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HistoryStore history = new JsonlHistoryStore(dataDir.resolve("historico.jsonl"));
        DictionaryStore dictionary = new JsonDictionaryStore(dataDir.resolve("dicionario.json"));
        SettingsStore settings = WindowsStartup.system().wrap(new JsonSettingsStore(dataDir.resolve("config.json")));
        Transcriber transcriber = makeTranscriber.apply(settings);
        DefaultDictationEngine engine = new DefaultDictationEngine(history, dictionary, transcriber,
                new Microphone(() -> settings.load().microphone()), new ClipboardPaster());
        try {
            return new AraraCore(history, dictionary, settings, transcriber, engine, GlobalHotkey.register(engine, settings));
        } catch (NativeHookException e) {
            engine.close();
            throw new IllegalStateException("Não consegui registrar o atalho global.", e);
        }
    }

    /**
     * Baixa (na primeira vez) e carrega o modelo escolhido nas configurações. Bloqueia: chame fora da EDT.
     * Chame de novo depois de trocar o modelo. progress vai de 0.0 a 1.0 durante o download.
     */
    public void prepareModel(DoubleConsumer progress) throws IOException {
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
    public void close() {
        hotkey.close();
        engine.close();
        if (transcriber instanceof WhisperTranscriber w) w.close(); // espera a transcrição em andamento
    }
}
