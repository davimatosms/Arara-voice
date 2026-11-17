package br.arara.core;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Grava, transcreve, aplica o dicionário, salva no histórico e só então cola. Ditado longo é transcrito aos
 * pedaços durante a fala, para no fim só faltar um resto curto: corta nas pausas e, quando o trecho fica longo para
 * a velocidade do modelo, no primeiro respiro entre palavras (fala corrida quase não tem pausa de 0,6 s).
 * Enquanto o trecho não volta do Whisper, uma prévia com o modelo leve mostra o que já foi dito (nunca vai para o
 * texto colado). A prévia refaz o trecho em andamento a cada {@link #PREVIEW_EVERY}; a de um trecho já cortado fica na
 * tela até ele voltar do Whisper, e a do trecho seguinte começa um pouco antes, onde ela parou, para não abrir buraco.
 */
final class DefaultDictationEngine implements DictationEngine {
    interface AudioSource {
        void start(Consumer<short[]> onChunk) throws Exception;

        void stop();
    }

    interface Paster {
        void paste(String text) throws Exception;
    }

    static final int TAP = Vad.RATE / 4; // 250 ms: toque mais curto que isto no modo segurar cancela
    private boolean holding; // modo segurar para falar, com o lock do engine
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
    // A prévia rouba CPU do modelo escolhido durante a fala, e o último trecho cortado no respiro ainda roda quando a
    // pessoa para: prévia demais atrasa o fim. Medido (PRECISO em CPU, fala corrida, Ryzen 5 5500): prévia a cada 1,2 s
    // de 6-8 s de áudio, RTF 0,47 -> 0,58 e espera final +2,1 s; refazer cada trecho cortado inteiro (8 s, sempre junto
    // com o Whisper dele), +1,5 s; assim (a cada 2,4 s, só o trecho em andamento), +0,0-0,15 s, com a fala na tela ~2 s
    // depois de dita (p50; p90 ~3 s; sem prévia, p50 ~8,5 s).
    static final int PREVIEW_EVERY = Vad.RATE * 24 / 10; // refaz o trecho em andamento a cada 2,4 s de fala nova
    static final int PREVIEW_MAX = Vad.RATE * 12; // trecho em andamento mais longo que isto: só o fim
    static final int PREVIEW_OVERLAP = Vad.RATE / 2; // emenda com a prévia do trecho anterior; o join tira a repetição
    // Prévias que demoram mais que isto 3 vezes seguidas (CPU fraca ou ocupada) chegam velhas e ainda disputam CPU com o
    // trecho final: o ditado segue sem elas (uma só lenta acontece em pico de CPU e não diz nada). Medido junto com o
    // PRECISO: p50 0,75 s, picos isolados de 1-3 s.
    static final double PREVIEW_SLOW = 2.0;
    private final HistoryStore history;
    private final DictionaryStore dictionary;
    private final Transcriber transcriber;
    private final AudioSource mic;
    private final Paster paster;
    private final Transcriber preview; // modelo leve para a prévia; null: sem prévia
    private final List<DictationListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-transcricao");
        t.setDaemon(true);
        return t;
    });

    // Som de início: o microfone abre ao mesmo tempo, então zera o áudio dele (duração + folga da saída de áudio)
    // em vez de bloquear o motor esperando o som acabar. Som de fim toca depois do mic.stop(): nada a descartar.
    private static final int SOUND_MARGIN_MS = 60;
    private SettingsStore soundSettings; // null: sem som (testes)
    private int skip; // amostras de 16 kHz a descartar no começo
    private DictationState state = DictationState.IDLE;
    private Vad vad;
    private short[] audio;
    private int size;
    private Take take;
    private int partStart; // amostra onde começa o trecho ainda não enviado ao Whisper
    private long partSpeech; // primeira fala desse trecho, ou -1
    private final ExecutorService previewer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-previa");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicBoolean previewBusy = new AtomicBoolean(); // rodada ocupada: pula, sem fila
    private long previewSpeech; // fim da fala na última prévia: em silêncio não repete
    private int liveEnd; // fim do áudio da última prévia do trecho em andamento, ou -1
    private int prevLiveEnd = -1; // idem, do último trecho cortado que teve prévia
    private volatile double rtf = 1; // segundos de Whisper por segundo de áudio no último trecho; começa pessimista
    Consumer<String> trace = s -> {}; // diagnóstico de latência (o e2e liga)

    /** Um ditado: os trechos rodam em ordem na thread de transcrição e juntam o texto aqui. */
    private static final class Take {
        final StringBuilder text = new StringBuilder(); // só a thread de transcrição mexe
        boolean failed; // idem
        volatile boolean cancelled;
        boolean delivering; // já passou da checagem de cancelled e vai colar; muda com o lock do engine
        int confirmed; // fim (amostra) do último trecho já transcrito; muda com o lock do engine
        volatile String confirmedText = "";
        final TreeMap<Integer, String> drafts = new TreeMap<>(); // prévia por início de trecho ainda não confirmado; idem
        volatile boolean noPreview; // prévia lenta demais neste ditado
        int slowPreviews; // seguidas; só a thread da prévia mexe
    }

    DefaultDictationEngine(HistoryStore history, DictionaryStore dictionary, Transcriber transcriber,
            AudioSource mic, Paster paster) {
        this(history, dictionary, transcriber, mic, paster, null);
    }

    DefaultDictationEngine(HistoryStore history, DictionaryStore dictionary, Transcriber transcriber,
            AudioSource mic, Paster paster, Transcriber preview) {
        this.preview = preview;
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
    public synchronized void press() {
        if (recording() || state == DictationState.TRANSCRIBING) return;
        start();
        holding = recording();
    }

    @Override
    public synchronized void release() {
        if (!holding || !recording()) return;
        if (size < TAP) cancel(); // toque curto: ninguém falou
        else finish();
    }

    @Override
    public synchronized void cancel() {
        if (state == DictationState.TRANSCRIBING) { // Esc na transcrição: a thread de fundo vê cancelled e não cola
            if (take.delivering) return; // já está colando: tarde demais para cancelar
            take.cancelled = true;
            setState(DictationState.IDLE);
            return;
        }
        if (!recording()) return;
        holding = false;
        mic.stop();
        sound(false);
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
        previewer.shutdown();
    }

    private boolean recording() {
        return state == DictationState.LISTENING || state == DictationState.SILENCE;
    }

    void setSoundSettings(SettingsStore store) {
        soundSettings = store;
    }

    private void sound(boolean start) {
        if (soundSettings != null) Sounds.play(soundSettings.load(), start);
    }

    private void start() {
        holding = false;
        vad = new Vad();
        audio = new short[Vad.RATE * 10];
        size = 0;
        take = new Take();
        partStart = 0;
        partSpeech = -1;
        previewSpeech = 0;
        liveEnd = prevLiveEnd = -1;
        skip = 0;
        if (soundSettings != null) {
            Settings cfg = soundSettings.load();
            if (cfg.soundOn() && cfg.soundVolume() > 0) skip = (Sounds.millis(cfg) + SOUND_MARGIN_MS) * Vad.RATE / 1000;
        }
        try {
            mic.start(this::onChunk); // os blocos esperam o lock: nenhum chega antes do LISTENING
        } catch (Exception e) {
            fail("Não consegui abrir o microfone.");
            return;
        }
        sound(true);
        setState(DictationState.LISTENING);
    }

    private synchronized void onChunk(short[] chunk) {
        if (!recording()) return;
        if (skip > 0) { // ainda é o som de início: zera as amostras (mantém a duração do áudio e o corte do VAD)
            skip -= chunk.length;
            Arrays.fill(chunk, (short) 0);
        }
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
            submit(cutPart(to), to);
            if (liveEnd >= 0) prevLiveEnd = liveEnd;
            liveEnd = -1;
        }
        if (preview != null) maybePreview();
        float level = vad.level();
        for (DictationListener l : listeners) l.onLevel(level);
        switch (r) {
            case LISTENING -> setState(DictationState.LISTENING);
            case SILENCE -> setState(DictationState.SILENCE);
            case TIMEOUT -> {
                if (!holding) finish(); // segurando, só soltar encerra
            } // 5 min sem fala: transcreve, salva e cola o que já foi dito
        }
    }

    private void finish() {
        holding = false;
        mic.stop();
        sound(false);
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
            if (rest != null) transcribePart(t, rest, Integer.MAX_VALUE);
            deliver(t);
        });
    }

    private int softPart() {
        return (int) Math.min(MAX_SOFT, Math.max(SOFT_PART, TARGET / rtf * Vad.RATE));
    }

    private void submit(short[] pcm, int to) {
        if (pcm == null) return;
        Take t = take;
        worker.execute(() -> transcribePart(t, pcm, to));
    }

    /**
     * Com fala nova desde a última prévia, manda o trecho em andamento para o modelo leve, na thread da prévia. Se o
     * trecho anterior ainda não voltou do Whisper, começa onde a prévia dele parou (o fim dele não ficou sem prévia).
     * Se a anterior ainda roda, pula esta rodada (sem fila).
     */
    private void maybePreview() {
        Take t = take;
        long end = vad.speechEnd();
        if (t.noPreview || end - previewSpeech < PREVIEW_EVERY || end <= partStart) return;
        if (!previewBusy.compareAndSet(false, true)) return;
        previewSpeech = end;
        boolean tail = prevLiveEnd > t.confirmed; // o trecho anterior ainda não voltou
        int from = Math.max(tail ? Math.max(t.confirmed, prevLiveEnd - PREVIEW_OVERLAP) : partStart, size - PREVIEW_MAX);
        short[] pcm = speechOnly(audio, from, size, tail ? from : Math.max(from, partSpeech), end);
        int key = partStart, to = size;
        liveEnd = size;
        trace.accept("previa " + from + " " + to);
        previewer.execute(() -> runPreview(t, key, to, pcm));
    }

    /** key: início do trecho dono desta prévia; to: fim do áudio dela. */
    private void runPreview(Take t, int key, int to, short[] pcm) {
        try {
            long start = System.nanoTime();
            String text = preview.transcribe(pcm, t.confirmedText);
            double took = (System.nanoTime() - start) / 1e9;
            t.slowPreviews = took > PREVIEW_SLOW ? t.slowPreviews + 1 : 0;
            if (t.slowPreviews >= 3) t.noPreview = true;
            trace.accept("previa-fim " + took);
            if (text == null) return; // modelo leve ainda não está pronto
            synchronized (this) {
                // Cancelou, parou, ou o trecho já confirmou: esta prévia já não vale.
                if (t != take || t.cancelled || !recording() || to <= t.confirmed) return;
                t.drafts.put(key, text.strip());
                trace.accept("previa-ok " + to);
                emitLive(t);
            }
        } catch (RuntimeException e) {
            t.noPreview = true; // a prévia é opcional: o ditado segue sem ela
        } finally {
            previewBusy.set(false);
        }
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
    private void transcribePart(Take t, short[] pcm, int to) {
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
        String soFar = t.text.toString();
        t.confirmedText = soFar;
        synchronized (this) { // junto com a prévia: nenhuma prévia velha chega depois deste texto
            t.confirmed = to;
            t.drafts.headMap(to).clear(); // a prévia deste trecho sai: o texto dele chegou
            if (t.cancelled) return;
            for (DictationListener l : listeners) l.onPartialText(soFar);
            emitLive(t);
        }
    }

    /** Texto ao vivo: confirmado + prévias dos trechos que ainda não voltaram do modelo escolhido. Com o lock. */
    private void emitLive(Take t) {
        StringBuilder all = new StringBuilder(t.confirmedText);
        int confirmed = all.length();
        for (String d : t.drafts.values()) join(all, d); // tira a palavra repetida nas emendas
        String provisional = all.substring(confirmed).strip();
        for (DictationListener l : listeners) l.onLiveText(t.confirmedText, provisional);
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

    /** O lock cobre só a checagem: histórico e colagem (com a espera do clipboard) rodam sem travar o atalho. */
    private void deliver(Take t) {
        synchronized (this) {
            if (t.cancelled) return;
            t.delivering = true; // daqui em diante o cancel() não desfaz
        }
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
        } catch (LinuxPaste.CopiedOnly e) {
            fail("Texto copiado: cole com Ctrl+V");
            return;
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
