package br.arara.app.demo;

import br.arara.core.DictationEngine;
import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.DictionaryStore;
import br.arara.core.HistoryEntry;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;
import br.arara.core.Term;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backend falso, só em memória, com os dados de exemplo do design. Mesmos métodos que o
 * {@code AraraCore} real vai ter, para a troca no Main ser de uma linha.
 */
public final class DemoCore {
    private final History history = new History();
    private final Dictionary dictionary = new Dictionary();
    private final SettingsMemory settings = new SettingsMemory();
    private final Engine engine = new Engine(history, dictionary);

    public DictationEngine engine() { return engine; }
    public HistoryStore history() { return history; }
    public DictionaryStore dictionary() { return dictionary; }
    public SettingsStore settings() { return settings; }

    private static final class History implements HistoryStore {
        private final List<HistoryEntry> entries = new ArrayList<>();

        History() {
            LocalDateTime now = LocalDateTime.now();
            entries.add(new HistoryEntry(now.minusSeconds(30), "Oi, pessoal! Só passando para avisar que a reunião de "
                    + "amanhã mudou para as dez. Quem não puder, me avisa por aqui que a gente remarca."));
            entries.add(new HistoryEntry(now.minusMinutes(12),
                    "Precisamos revisar o contrato antes de sexta, beleza? Me manda o PDF."));
            entries.add(new HistoryEntry(now.minusMinutes(40), ""));
            entries.add(new HistoryEntry(LocalDate.now().atTime(9, 15).isBefore(now.minusMinutes(40))
                    ? LocalDate.now().atTime(9, 15) : now.minusHours(1),
                    "Lembrar de comprar pão de queijo e café no caminho de casa."));
            entries.add(new HistoryEntry(LocalDate.now().minusDays(1).atTime(18, 2),
                    "O build com Vulkan ficou bem mais rápido que na CPU, testei com o modelo turbo."));
        }

        public synchronized void append(HistoryEntry entry) { entries.add(0, entry); }
        public synchronized List<HistoryEntry> all() { return List.copyOf(entries); }
        public synchronized void clear() { entries.clear(); }
    }

    private static final class Dictionary implements DictionaryStore {
        private final List<Term> terms = new ArrayList<>(List.of(
                new Term("uísper", "Whisper"), new Term("jota pê ene i", "JNI"), new Term("vê esse code", "VS Code"),
                new Term("pê tê bê erre", "PT-BR"), new Term("arara", "Arara")));

        public synchronized List<Term> all() { return List.copyOf(terms); }
        public synchronized void add(Term term) { terms.add(term); }
        public synchronized void remove(Term term) { terms.remove(term); }

        public synchronized String apply(String text) {
            for (Term t : terms) {
                text = Pattern.compile(Pattern.quote(t.ouvir()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(text).replaceAll(Matcher.quoteReplacement(t.escrever()));
            }
            return text;
        }
    }

    private static final class SettingsMemory implements SettingsStore {
        private volatile Settings settings = Settings.defaults();

        public Settings load() { return settings; }
        public void save(Settings s) { settings = s; }
    }

    /**
     * Simula o ditado numa thread própria, como o backend real: fala 4 s, fica em silêncio 4 s e repete.
     * No segundo toggle "transcreve" por 1,2 s, salva, "cola" e volta a IDLE 1,5 s depois.
     */
    private static final class Engine implements DictationEngine {
        private static final String[] PHRASES = {
                "Testando o Arara com o uísper rodando aqui no computador.",
                "Amanhã eu mando o relatório em PT-BR, pode deixar.",
                "Lembrar de abrir o vê esse code e revisar o PR antes do almoço."};

        private final List<DictationListener> listeners = new CopyOnWriteArrayList<>();
        private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "arara-demo");
            t.setDaemon(true);
            return t;
        });
        private final History history;
        private final Dictionary dictionary;
        private DictationState state = DictationState.IDLE;
        private ScheduledFuture<?> mic;
        private int phrase;

        Engine(History history, Dictionary dictionary) {
            this.history = history;
            this.dictionary = dictionary;
        }

        public synchronized void toggle() {
            switch (state) {
                case IDLE, PASTED, ERROR -> start();
                case LISTENING, SILENCE -> stop();
                case TRANSCRIBING -> { }
            }
        }

        public synchronized void cancel() {
            if (mic != null) mic.cancel(false);
            if (state != DictationState.IDLE) set(DictationState.IDLE);
        }

        public synchronized DictationState state() { return state; }
        public void addListener(DictationListener l) { listeners.add(l); }
        public void removeListener(DictationListener l) { listeners.remove(l); }
        public void close() { thread.shutdownNow(); }

        private void start() {
            set(DictationState.LISTENING);
            long t0 = System.nanoTime();
            mic = thread.scheduleAtFixedRate(() -> {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                boolean talking = ms % 8000 < 4000;
                float level = talking ? 0.25f + (float) Math.random() * 0.7f : (float) Math.random() * 0.03f;
                for (DictationListener l : listeners) l.onLevel(level);
                synchronized (this) {
                    DictationState want = talking ? DictationState.LISTENING
                            : ms % 8000 > 5500 ? DictationState.SILENCE : state;
                    if (want != state) set(want);
                }
            }, 0, 33, TimeUnit.MILLISECONDS);
        }

        private void stop() {
            mic.cancel(false);
            set(DictationState.TRANSCRIBING);
            thread.schedule(() -> {
                HistoryEntry entry;
                synchronized (this) {
                    if (state != DictationState.TRANSCRIBING) return; // cancelado
                    entry = new HistoryEntry(LocalDateTime.now(), dictionary.apply(PHRASES[phrase++ % PHRASES.length]));
                    history.append(entry);
                }
                for (DictationListener l : listeners) l.onResult(entry);
                synchronized (this) {
                    set(DictationState.PASTED);
                }
                thread.schedule(() -> {
                    synchronized (this) {
                        if (state == DictationState.PASTED) set(DictationState.IDLE);
                    }
                }, 1500, TimeUnit.MILLISECONDS);
            }, 1200, TimeUnit.MILLISECONDS);
        }

        private void set(DictationState s) {
            state = s;
            for (DictationListener l : listeners) l.onState(s);
        }
    }
}
