package br.arara.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Grava, transcreve, aplica o dicionário, salva no histórico e só então cola. Ditado longo é transcrito aos
 * pedaços durante a fala (corta nas pausas), para no fim só faltar o último trecho.
 */
final class DefaultDictationEngine implements DictationEngine {
    interface AudioSource {
        void start(Consumer<short[]> onChunk) throws Exception;

        void stop();
    }

    interface Paster {
        void paste(String text) throws Exception;
    }

    private static final int EDGE = Vad.RATE; // 1 s
    static final int PAUSE = Vad.RATE * 6 / 10; // 0,6 s de silêncio: fim de frase (entre palavras fica bem abaixo)
    static final int MIN_PART = Vad.RATE * 4; // trecho curto demais perde contexto e quase não adianta
    static final int MAX_PART = Vad.RATE * 20; // sem pausa até aqui, corta mesmo assim (o Whisper vai até 30 s)
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
    private Take take;
    private int partStart; // amostra onde começa o trecho ainda não enviado ao Whisper
    private long partSpeech; // primeira fala desse trecho, ou -1

    /** Um ditado: os trechos rodam em ordem na thread de transcrição e juntam o texto aqui. */
    private static final class Take {
        final StringBuilder text = new StringBuilder(); // só a thread de transcrição mexe
        boolean failed; // idem
        volatile boolean cancelled;
    }

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
        take.cancelled = true; // trechos na fila são descartados; o que está no Whisper termina e é ignorado
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
        take = new Take();
        partStart = 0;
        partSpeech = -1;
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
        long end = vad.speechEnd();
        if (partSpeech < 0 && end > partStart) partSpeech = Math.max(partStart, end - (long) Vad.SPEECH_CHUNKS * chunk.length);
        boolean pause = partSpeech >= 0 && size - end >= PAUSE && end - partSpeech >= MIN_PART;
        if (pause || size - partStart >= MAX_PART) {
            short[] pcm = cutPart();
            Take t = take;
            if (pcm != null) worker.execute(() -> transcribePart(t, pcm));
        }
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
        // Whisper inventa frases em áudio sem fala ("A gente pode precisar."): nem chega a transcrever.
        if (!vad.heardSpeech()) {
            audio = null;
            fail("Não detectei fala.");
            return;
        }
        short[] rest = cutPart();
        audio = null;
        Take t = take;
        setState(DictationState.TRANSCRIBING);
        worker.execute(() -> {
            if (rest != null) transcribePart(t, rest);
            deliver(t);
        });
    }

    /** Tira do buffer o trecho pendente até agora, só a parte com fala; null se não houve fala nele. */
    private short[] cutPart() {
        short[] pcm = partSpeech < 0 ? null : speechOnly(audio, partStart, size, partSpeech, vad.speechEnd());
        partStart = size;
        partSpeech = -1;
        return pcm;
    }

    /**
     * Corta o silêncio antes da primeira fala e depois da última, com folga para sílabas fracas. Silêncio longo
     * nas pontas faz o Whisper inventar frases (medido: "Obrigado. Obrigado. Obrigado.") e gasta tempo de CPU.
     */
    static short[] speechOnly(short[] audio, int size, Vad vad) {
        return speechOnly(audio, 0, size, vad.speechStart(), vad.speechEnd());
    }

    static short[] speechOnly(short[] audio, int from, int to, long speechStart, long speechEnd) {
        return Arrays.copyOfRange(audio, (int) Math.max(from, speechStart - EDGE), (int) Math.min(to, speechEnd + EDGE));
    }

    /** Roda na thread de transcrição, fora do lock, para o Whisper não travar o atalho nem o microfone. */
    private void transcribePart(Take t, short[] pcm) {
        if (t.cancelled || t.failed) return;
        try {
            // O texto anterior vai de contexto: mantém pontuação e continuidade entre os trechos.
            join(t.text, transcriber.transcribe(pcm, t.text.toString()));
        } catch (RuntimeException e) {
            t.failed = true;
        }
    }

    /** Junta com um espaço e tira a palavra repetida na fronteira (o Whisper às vezes repete o fim do contexto). */
    static void join(StringBuilder text, String part) {
        String[] next = part.strip().split("\\s+", 2);
        if (next[0].isEmpty()) return;
        if (text.length() > 0) {
            String[] prev = text.toString().split("\\s+");
            String word = bare(next[0]);
            if (!word.isEmpty() && word.equals(bare(prev[prev.length - 1]))) {
                if (next.length == 1) return;
                next = new String[] {next[1]};
            }
            text.append(' ');
        }
        text.append(String.join(" ", next));
    }

    private static String bare(String word) {
        return word.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private void deliver(Take t) {
        if (t.cancelled) return;
        String text;
        try {
            if (t.failed) throw new IllegalStateException("trecho falhou");
            text = dictionary.apply(t.text.toString());
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
