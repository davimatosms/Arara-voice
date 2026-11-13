package br.arara.core;

import com.github.kwhat.jnativehook.NativeHookException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ponto de entrada do backend para o app: liga armazenamento, microfone, motor e atalho global. */
public final class AraraCore implements AutoCloseable {
    private final HistoryStore history;
    private final DictionaryStore dictionary;
    private final SettingsStore settings;
    private final DefaultDictationEngine engine;
    private final GlobalHotkey hotkey;

    private AraraCore(HistoryStore history, DictionaryStore dictionary, SettingsStore settings,
            DefaultDictationEngine engine, GlobalHotkey hotkey) {
        this.history = history;
        this.dictionary = dictionary;
        this.settings = settings;
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

    /** @throws IllegalStateException se não der para registrar o atalho global */
    public static AraraCore start(Path dataDir, Transcriber transcriber) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HistoryStore history = new JsonlHistoryStore(dataDir.resolve("historico.jsonl"));
        DictionaryStore dictionary = new JsonDictionaryStore(dataDir.resolve("dicionario.json"));
        SettingsStore settings = new JsonSettingsStore(dataDir.resolve("config.json"));
        DefaultDictationEngine engine = new DefaultDictationEngine(history, dictionary, transcriber,
                new Microphone(() -> settings.load().microphone()), new ClipboardPaster());
        try {
            return new AraraCore(history, dictionary, settings, engine, GlobalHotkey.register(engine, settings));
        } catch (NativeHookException e) {
            engine.close();
            throw new IllegalStateException("Não consegui registrar o atalho global.", e);
        }
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
    }
}
