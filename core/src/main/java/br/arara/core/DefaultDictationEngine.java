package br.arara.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Grava, transcreve, aplica o dicionário, salva no histórico e só então cola. Ditado longo é transcrito aos
 * pedaços durante a fala, para no fim só faltar um resto curto: corta nas pausas e, quando o trecho fica longo para
 * a velocidade do modelo, no primeiro respiro entre palavras (fala corrida quase não tem pausa de 0,6 s).
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
    // Cada corte fora de pausa custa precisão (medido com trechos de ~4 s: WER 2% -> 16-70%; com 6 s, 4-8%: o Whisper
    // repete o contexto e entra em laço). Então só corta no respiro o necessário para o resto final levar ~TARGET de
    // Whisper: o PRECISO em CPU (0,55 s por s de áudio) corta a cada 8 s; o LEVE e o EQUILIBRADO quase só nas pausas.
    private static final double TARGET = 3; // segundos de Whisper para o último trecho
    static final int SOFT_PART = Vad.RATE * 8; // trecho mínimo para cortar no respiro
    private static final int MAX_SOFT = Vad.RATE * 16;
    static final int NO_BREATH = Vad.RATE * 4; // sem respiro por mais 4 s: corta no ponto mais baixo dos últimos 2 s
    // 150 ms abaixo do limiar de fala: espaço entre palavras. Cortar no ponto mais baixo de 20 ms partia palavras nas
    // oclusivas (p, t, k: < 100 ms de silêncio) e o Whisper inventava texto na emenda (medido: WER 2% -> 16-64%).
    static final int GAP = Vad.RATE * 15 / 100;
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
    private volatile double rtf = 1; // segundos de Whisper por segundo de áudio no último trecho; começa pessimista
    Consumer<String> trace = s -> {}; // diagnóstico de latência (o e2e liga)

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
        long spoken = partSpeech < 0 ? 0 : end - partSpeech;
        int to = -1;
        String why = null;
        if (spoken >= MIN_PART && size - end >= PAUSE) {
            to = size;
            why = "pausa";
        } else if (size - partStart >= softPart() + NO_BREATH) {
            to = quietest(audio, size - 2 * Vad.RATE, size);
            why = "max";
        } else if (spoken >= softPart()) {
            int gap = quietest(audio, size - Vad.RATE, size);
            if (Vad.dbfs(Arrays.copyOfRange(audio, gap - GAP / 2, gap + GAP / 2)) < vad.threshold()) to = gap;
            why = "respiro";
        }
        if (to >= 0) {
            trace.accept("corte " + why + " " + to);
            submit(cutPart(to));
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
        trace.accept("corte fim " + size);
        short[] rest = cutPart(size);
        audio = null;
        Take t = take;
        setState(DictationState.TRANSCRIBING);
        worker.execute(() -> {
            if (rest != null) transcribePart(t, rest);
            deliver(t);
        });
    }

    private int softPart() {
        return (int) Math.min(MAX_SOFT, Math.max(SOFT_PART, TARGET / rtf * Vad.RATE));
    }

    private void submit(short[] pcm) {
        if (pcm == null) return;
        Take t = take;
        worker.execute(() -> transcribePart(t, pcm));
    }

    /**
     * Tira do buffer o trecho pendente até {@code to}, só a parte com fala; null se não houve fala nele. Se a fala
     * continua depois de {@code to}, o próximo trecho começa ali com fala.
     */
    private short[] cutPart(int to) {
        long end = vad.speechEnd();
        short[] pcm = partSpeech < 0 || partSpeech >= to ? null : speechOnly(audio, partStart, to, partSpeech, end);
        partStart = to;
        partSpeech = end > to ? to : -1;
        return pcm;
    }

    /** Meio da janela de {@link #GAP} mais silenciosa entre from e to (de 20 em 20 ms). */
    static int quietest(short[] audio, int from, int to) {
        int best = to - GAP / 2;
        double min = Double.MAX_VALUE;
        for (int i = from; i + GAP <= to; i += Vad.RATE / 50) {
            double e = 0;
            for (int j = i; j < i + GAP; j++) e += (double) audio[j] * audio[j];
            if (e < min) {
                min = e;
                best = i + GAP / 2;
            }
        }
        return best;
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
            long start = System.nanoTime();
            String part = transcriber.transcribe(pcm, t.text.toString());
            rtf = (System.nanoTime() - start) / 1e9 / (pcm.length / (double) Vad.RATE);
            join(t.text, part);
        } catch (RuntimeException e) {
            t.failed = true;
            return;
        }
        if (t.cancelled) return;
        String soFar = t.text.toString();
        for (DictationListener l : listeners) l.onPartialText(soFar);
    }

    /**
     * Junta com um espaço. Tira o começo do trecho que repete o texto anterior (com o contexto no prompt, o Whisper às
     * vezes repete a última palavra, ou 3+ palavras das últimas 20) e as repetições seguidas dentro do trecho (laço do
     * Whisper: "o time de design, o time de design, ...").
     */
    static void join(StringBuilder text, String part) {
        List<String> next = collapseLoops(part.strip().split("\\s+"));
        if (next.get(0).isEmpty()) return;
        List<String> prev = Arrays.asList(text.toString().strip().split("\\s+"));
        int n = prev.size(), drop = 0;
        for (int k = Math.min(next.size(), 15); k > 0 && drop == 0; k--) {
            for (int j = n - k; j >= Math.max(0, n - 20) && (k >= 3 || j == n - k); j--) {
                if (same(prev, j, next, 0, k)) {
                    drop = k;
                    break;
                }
            }
        }
        next = next.subList(drop, next.size());
        if (next.isEmpty()) return;
        if (text.length() > 0) text.append(' ');
        text.append(String.join(" ", next));
    }

    /** Tira cada repetição seguida de 3 palavras ou mais, deixando uma. */
    static List<String> collapseLoops(String[] words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            out.add(w);
            for (int n = 3, s = out.size(); 2 * n <= s; n++) {
                if (same(out, s - 2 * n, out, s - n, n)) {
                    out.subList(s - n, s).clear();
                    break;
                }
            }
        }
        return out;
    }

    private static boolean same(List<String> a, int i, List<String> b, int j, int n) {
        for (int k = 0; k < n; k++) {
            String w = bare(a.get(i + k));
            if (w.isEmpty() || !w.equals(bare(b.get(j + k)))) return false;
        }
        return true;
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
