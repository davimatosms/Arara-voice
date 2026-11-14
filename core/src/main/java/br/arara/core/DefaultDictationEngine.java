package br.arara.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Grava, transcreve, aplica o dicionário, salva no histórico e só então cola. */
final class DefaultDictationEngine implements DictationEngine {
    interface AudioSource {
        void start(Consumer<short[]> onChunk) throws Exception;

        void stop();
    }

    interface Paster {
        void paste(String text) throws Exception;
    }

    private static final int EDGE = Vad.RATE; // 1 s
    private final HistoryStore history;
    private final DictionaryStore dictionary;
    private final Transcriber transcriber;
    private final AudioSource mic;
    private final Paster paster;
    private final List<DictationListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-transcricao");
        t.setDaemon(true);
        return t;
    });

    private DictationState state = DictationState.IDLE;
    private Vad vad;
    private short[] audio;
    private int size;

    DefaultDictationEngine(HistoryStore history, DictionaryStore dictionary, Transcriber transcriber,
            AudioSource mic, Paster paster) {
        this.history = history;
        this.dictionary = dictionary;
        this.transcriber = transcriber;
        this.mic = mic;
        this.paster = paster;
    }

    @Override
    public synchronized void toggle() {
        switch (state) {
            case LISTENING, SILENCE -> finish();
            case TRANSCRIBING -> {} // espera terminar
            default -> start();
        }
    }

    @Override
    public synchronized void cancel() {
        if (!recording()) return;
        mic.stop();
        audio = null;
        setState(DictationState.IDLE);
    }

    @Override
    public synchronized DictationState state() {
        return state;
    }

    @Override
    public void addListener(DictationListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(DictationListener listener) {
        listeners.remove(listener);
    }

    @Override
    public synchronized void close() {
        cancel();
        worker.shutdown();
    }

    private boolean recording() {
        return state == DictationState.LISTENING || state == DictationState.SILENCE;
    }

    private void start() {
        vad = new Vad();
        audio = new short[Vad.RATE * 10];
        size = 0;
        try {
            mic.start(this::onChunk); // os blocos esperam o lock: nenhum chega antes do LISTENING
        } catch (Exception e) {
            fail("Não consegui abrir o microfone.");
            return;
        }
        setState(DictationState.LISTENING);
    }

    private synchronized void onChunk(short[] chunk) {
        if (!recording()) return;
        if (size + chunk.length > audio.length) audio = Arrays.copyOf(audio, Math.max(audio.length * 2, size + chunk.length));
        System.arraycopy(chunk, 0, audio, size, chunk.length);
        size += chunk.length;
        Vad.Result r = vad.feed(chunk);
        float level = vad.level();
        for (DictationListener l : listeners) l.onLevel(level);
        switch (r) {
            case LISTENING -> setState(DictationState.LISTENING);
            case SILENCE -> setState(DictationState.SILENCE);
            case TIMEOUT -> finish(); // 5 min sem fala: transcreve, salva e cola o que já foi dito
        }
    }

    private void finish() {
        mic.stop();
        short[] all = audio;
        audio = null;
        // Whisper inventa frases em áudio sem fala ("A gente pode precisar."): nem chega a transcrever.
        if (!vad.heardSpeech()) {
            fail("Não detectei fala.");
            return;
        }
        short[] pcm = speechOnly(all, size, vad);
        setState(DictationState.TRANSCRIBING);
        worker.execute(() -> transcribe(pcm));
    }

    /**
     * Corta o silêncio antes da primeira fala e depois da última, com folga para sílabas fracas. Silêncio longo
     * nas pontas faz o Whisper inventar frases (medido: "Obrigado. Obrigado. Obrigado.") e gasta tempo de CPU.
     */
    static short[] speechOnly(short[] audio, int size, Vad vad) {
        int from = (int) Math.max(0, vad.speechStart() - EDGE);
        int to = (int) Math.min(size, vad.speechEnd() + EDGE);
        return Arrays.copyOfRange(audio, from, to);
    }

    /** Roda na thread de transcrição, fora do lock, para o Whisper não travar o atalho. */
    private void transcribe(short[] pcm) {
        String text;
        try {
            text = dictionary.apply(transcriber.transcribe(pcm).strip());
        } catch (RuntimeException e) {
            fail("Falha ao transcrever o áudio.");
            return;
        }
        if (text.isEmpty()) {
            fail("Não detectei fala.");
            return;
        }
        HistoryEntry entry = new HistoryEntry(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), text);
        boolean saved;
        try {
            history.append(entry);
            saved = true;
            for (DictationListener l : listeners) l.onResult(entry);
        } catch (RuntimeException e) {
            saved = false; // cola mesmo assim, senão o texto se perde
        }
        try {
            paster.paste(text);
        } catch (Exception e) {
            fail(saved ? "Não consegui colar. O texto está no histórico." : "Não consegui salvar nem colar o texto.");
            return;
        }
        if (saved) setState(DictationState.PASTED);
        else fail("Não consegui salvar no histórico.");
    }

    private synchronized void fail(String message) {
        setState(DictationState.ERROR);
        for (DictationListener l : listeners) l.onError(message);
    }

    private synchronized void setState(DictationState s) {
        if (state == s) return;
        state = s;
        for (DictationListener l : listeners) l.onState(s);
    }
}
