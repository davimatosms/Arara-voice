package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultDictationEngineTest {
    @TempDir
    Path dir;

    /** Microfone falso: o teste empurra os blocos na mão. */
    static class FakeMic implements DefaultDictationEngine.AudioSource {
        Consumer<short[]> onChunk;

        @Override
        public void start(Consumer<short[]> onChunk) {
            this.onChunk = onChunk;
        }

        @Override
        public void stop() {
            onChunk = null;
        }

        void push(short[] chunk) {
            onChunk.accept(chunk);
        }
    }

    /** Transcritor falso: devolve um texto fixo e guarda quantas amostras recebeu. */
    static class FakeTranscriber implements Transcriber {
        final String text;
        int samples = -1;

        FakeTranscriber(String text) {
            this.text = text;
        }

        @Override
        public String transcribe(short[] pcm16k) {
            samples = pcm16k.length;
            return text;
        }
    }

    final FakeMic mic = new FakeMic();
    final BlockingQueue<DictationState> states = new LinkedBlockingQueue<>();
    final List<String> errors = new ArrayList<>();
    HistoryStore history;
    DictionaryStore dictionary;
    Transcriber preview; // prévia com o modelo leve; null: sem prévia

    DefaultDictationEngine engine(Transcriber t, DefaultDictationEngine.Paster paster) {
        history = new JsonlHistoryStore(dir.resolve("historico.jsonl"));
        dictionary = new JsonDictionaryStore(dir.resolve("dicionario.json"));
        DefaultDictationEngine e = new DefaultDictationEngine(history, dictionary, t, mic, paster, preview);
        e.addListener(new DictationListener() {
            @Override
            public void onState(DictationState s) {
                states.add(s);
            }

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });
        return e;
    }

    DictationState next() throws InterruptedException {
        return states.poll(5, TimeUnit.SECONDS);
    }

    @Test
    void transcribesAppliesDictionarySavesThenPastes() throws Exception {
        List<String> pasted = new ArrayList<>();
        FakeTranscriber t = new FakeTranscriber("  oi davi  ");
        DefaultDictationEngine e = engine(t, text -> {
            assertEquals(1, history.all().size(), "salva antes de colar");
            pasted.add(text);
        });
        dictionary.add(new Term("davi", "Davi"));

        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        for (int i = 0; i < 4; i++) mic.push(VadTest.SPEECH);
        e.toggle();
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());

        assertEquals(4 * VadTest.CHUNK, t.samples);
        assertEquals(List.of("oi Davi"), pasted);
        assertEquals("oi Davi", history.all().get(0).texto());
        assertNull(mic.onChunk, "microfone desligado");
    }

    @Test
    void silenceAtTheEdgesIsCutBeforeTranscribing() throws Exception {
        FakeTranscriber t = new FakeTranscriber("oi");
        DefaultDictationEngine e = engine(t, text -> {});
        e.toggle();
        for (int i = 0; i < 100; i++) mic.push(VadTest.QUIET);  // ~3,2 s antes
        for (int i = 0; i < 10; i++) mic.push(VadTest.SPEECH);
        for (int i = 0; i < 150; i++) mic.push(VadTest.QUIET);  // ~4,8 s depois
        e.toggle();
        for (DictationState s : List.of(DictationState.LISTENING, DictationState.SILENCE, DictationState.LISTENING,
                DictationState.SILENCE, DictationState.TRANSCRIBING, DictationState.PASTED)) assertEquals(s, next());
        assertEquals(10 * VadTest.CHUNK + 2 * Vad.RATE, t.samples, "fala + 1 s de folga de cada lado");
    }

    @Test
    void pasteFailureKeepsTextInHistory() throws Exception {
        DefaultDictationEngine e = engine(new FakeTranscriber("texto"), text -> {
            throw new IllegalStateException("clipboard ocupado");
        });
        e.toggle();
        for (int i = 0; i < 5; i++) mic.push(VadTest.SPEECH);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.ERROR, next());
        assertEquals("texto", history.all().get(0).texto());
        assertEquals(1, errors.size());
    }

    @Test
    void cancelDiscardsWithoutTranscribing() throws Exception {
        FakeTranscriber t = new FakeTranscriber("nada");
        DefaultDictationEngine e = engine(t, text -> {});
        e.cancel(); // parado: não faz nada
        e.toggle();
        mic.push(VadTest.SPEECH);
        e.cancel();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.IDLE, next());
        assertEquals(-1, t.samples);
        assertTrue(history.all().isEmpty());
    }

    @Test
    void fiveMinutesOfSilenceFinishesAndPastes() throws Exception {
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new FakeTranscriber("o que deu tempo de falar"), pasted::add);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        for (int i = 0; i < 5; i++) mic.push(VadTest.SPEECH);
        for (long n = 0; n <= 5L * 60 * Vad.RATE && mic.onChunk != null; n += VadTest.CHUNK) mic.push(VadTest.QUIET);
        assertEquals(DictationState.SILENCE, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());
        assertEquals(List.of("o que deu tempo de falar"), pasted);
        assertFalse(history.all().isEmpty());
    }

    @Test
    void noSpeechSkipsTranscription() throws Exception {
        FakeTranscriber t = new FakeTranscriber("frase inventada");
        DefaultDictationEngine e = engine(t, text -> {});
        e.toggle();
        for (int i = 0; i < 30; i++) mic.push(VadTest.QUIET);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.ERROR, next());
        assertEquals(-1, t.samples);
        assertTrue(history.all().isEmpty());
    }

    /** Transcritor falso para trechos: devolve um texto por chamada e guarda o tamanho e o contexto recebidos. */
    static class PartsTranscriber implements Transcriber {
        final List<String> texts;
        final List<Integer> samples = new ArrayList<>();
        final List<String> contexts = new ArrayList<>();
        final List<String> threads = new ArrayList<>();

        PartsTranscriber(String... texts) {
            this.texts = List.of(texts);
        }

        @Override
        public String transcribe(short[] pcm16k) {
            throw new AssertionError("o engine sempre passa o contexto");
        }

        @Override
        public synchronized String transcribe(short[] pcm16k, String previous) {
            samples.add(pcm16k.length);
            contexts.add(previous);
            threads.add(Thread.currentThread().getName());
            return texts.get(samples.size() - 1);
        }
    }

    void push(short[] chunk, double seconds) {
        for (int i = 0; i < seconds * Vad.RATE / VadTest.CHUNK; i++) mic.push(chunk);
    }

    @Test
    void afterSoftLengthCutsAtBreathWithoutWaitingForPause() throws Exception {
        List<String> pasted = new ArrayList<>();
        List<String> partials = new ArrayList<>();
        List<String> partialThreads = new ArrayList<>();
        PartsTranscriber t = new PartsTranscriber("Primeira frase", "frase e o fim.");
        DefaultDictationEngine e = engine(t, pasted::add);
        e.addListener(new DictationListener() {
            @Override
            public void onPartialText(String textSoFar) { // só a thread de transcrição chama; o PASTED vem depois
                partials.add(textSoFar);
                partialThreads.add(Thread.currentThread().getName());
            }
        });
        e.toggle();
        push(VadTest.QUIET, 0.2);
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.3); // respiro antes de 8 s de fala: não corta
        push(VadTest.SPEECH, 2.5);
        push(VadTest.QUIET, 0.3); // respiro depois de 8 s: curto demais para ser pausa, mas é o ponto de corte
        push(VadTest.SPEECH, 2);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next(), "sem piscar estado durante a fala");
        assertEquals(DictationState.PASTED, next());

        assertEquals(2, t.samples.size());
        int first = t.samples.get(0);
        assertTrue(first >= 8.0 * Vad.RATE && first <= 8.3 * Vad.RATE, "cortou no segundo respiro: " + first);
        assertEquals(List.of("", "Primeira frase"), t.contexts);
        assertEquals(List.of("Primeira frase e o fim."), pasted, "palavra repetida na fronteira some");
        assertTrue(t.threads.stream().allMatch("arara-transcricao"::equals), "fora da thread do microfone");
        assertEquals(List.of("Primeira frase", "Primeira frase e o fim."), partials, "texto inteiro até agora");
        assertTrue(partialThreads.stream().allMatch("arara-transcricao"::equals), "não bloqueia o microfone");
    }

    @Test
    void busyWorkerQueuesPartsCutAtBreathPauseAndMaxLength() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        PartsTranscriber t = new PartsTranscriber("um", "dois", "três", "quatro") {
            @Override
            public String transcribe(short[] pcm16k, String previous) {
                busy.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                return super.transcribe(pcm16k, previous);
            }
        };
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(t, pasted::add);
        e.toggle();
        push(VadTest.SPEECH, 8); // sem respiro: ainda não corta
        assertEquals(1, busy.getCount(), "não partiu a fala");
        push(VadTest.QUIET, 0.3);
        push(VadTest.SPEECH, 1); // respiro depois de 8 s: corta ali
        assertTrue(busy.await(5, TimeUnit.SECONDS), "primeiro trecho no Whisper");
        push(VadTest.SPEECH, 4.5);
        push(VadTest.QUIET, 0.7); // pausa: corta, e o trecho espera na fila
        for (int i = 0; i < 13 * Vad.RATE / VadTest.CHUNK; i++) mic.push(i % 6 == 5 ? VadTest.QUIET : VadTest.SPEECH); // sem pausa
        e.toggle();
        release.countDown();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());

        assertEquals(4, t.samples.size());
        assertTrue(t.samples.get(0) >= 8 * Vad.RATE && t.samples.get(0) <= 8.3 * Vad.RATE, "no respiro: " + t.samples.get(0));
        assertTrue(t.samples.get(2) <= DefaultDictationEngine.SOFT_PART + DefaultDictationEngine.NO_BREATH
                && t.samples.get(2) > 10 * Vad.RATE,
                "cortou no máximo: " + t.samples.get(2));
        assertEquals(List.of("", "um", "um dois", "um dois três"), t.contexts);
        assertEquals(List.of("um dois três quatro"), pasted);
    }

    @Test
    void fastModelKeepsLongPartsAndCutsOnlyAtPauses() throws Exception {
        PartsTranscriber t = new PartsTranscriber("um.", "dois.");
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(t, pasted::add);
        e.toggle();
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.7); // pausa: primeiro trecho, que mostra o Whisper rápido
        assertEquals(DictationState.LISTENING, next());
        for (int i = 0; i < 100 && t.samples.isEmpty(); i++) Thread.sleep(10);
        push(VadTest.SPEECH, 8.5);
        push(VadTest.QUIET, 0.3); // respiro depois de 8 s: com modelo rápido não precisa cortar
        push(VadTest.SPEECH, 2);
        e.toggle();
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());
        assertEquals(2, t.samples.size());
        assertEquals(List.of("um. dois."), pasted);
    }

    @Test
    void quietestFindsTheGapInsideSpeech() {
        short[] a = new short[Vad.RATE];
        for (int i = 0; i < a.length; i++) a[i] = (short) (i >= 6000 && i < 9000 ? 0 : (i % 2 == 0 ? 3000 : -3000));
        int cut = DefaultDictationEngine.quietest(a, 0, a.length);
        assertTrue(cut - DefaultDictationEngine.GAP / 2 >= 6000 && cut + DefaultDictationEngine.GAP / 2 <= 9000, "corte " + cut);
    }

    @Test
    void cancelDropsPartInProgressAndQueuedParts() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> calls = new ArrayList<>();
        Transcriber t = new Transcriber() {
            @Override
            public String transcribe(short[] pcm16k) {
                synchronized (calls) {
                    calls.add(pcm16k.length);
                }
                busy.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                return "não pode colar";
            }
        };
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(t, pasted::add);
        List<String> partials = new CopyOnWriteArrayList<>();
        e.addListener(new DictationListener() {
            @Override
            public void onPartialText(String textSoFar) {
                partials.add(textSoFar);
            }
        });
        e.toggle();
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.7);
        assertTrue(busy.await(5, TimeUnit.SECONDS), "primeiro trecho no Whisper");
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.7); // segundo trecho fica na fila
        e.cancel();
        release.countDown();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.IDLE, next());
        assertNull(states.poll(500, TimeUnit.MILLISECONDS), "nada depois do cancelamento");
        assertEquals(1, calls.size(), "trecho da fila descartado");
        assertTrue(pasted.isEmpty());
        assertTrue(partials.isEmpty(), "texto parcial descartado junto");
        assertTrue(history.all().isEmpty());
    }

    @Test
    void joinAddsOneSpaceAndSkipsRepeatedBoundaryWord() {
        StringBuilder b = new StringBuilder();
        DefaultDictationEngine.join(b, "  Olá, mundo. ");
        DefaultDictationEngine.join(b, "");
        DefaultDictationEngine.join(b, "Mundo, tudo bem?");
        DefaultDictationEngine.join(b, "bem?");
        DefaultDictationEngine.join(b, "Sim.");
        assertEquals("Olá, mundo. tudo bem? Sim.", b.toString());
    }

    @Test
    void joinDropsRepeatedSentenceAndWhisperLoops() {
        StringBuilder b = new StringBuilder("Essa lista vai estar na pasta compartilhada.");
        DefaultDictationEngine.join(b, "Essa lista vai estar na pasta compartilhada. Até amanhã.");
        assertEquals("Essa lista vai estar na pasta compartilhada. Até amanhã.", b.toString());
        DefaultDictationEngine.join(b, "Na terça, o time de design... na terça, o time de design... na terça, o time de design apresentou.");
        assertEquals("Essa lista vai estar na pasta compartilhada. Até amanhã. Na terça, o time de design... apresentou.",
                b.toString());
        DefaultDictationEngine.join(b, "o time de design... apresentou as telas novas."); // repete o meio do anterior
        assertTrue(b.toString().endsWith("o time de design... apresentou. as telas novas."), b.toString());
        DefaultDictationEngine.join(b, "que que eu disse"); // repetição curta é fala normal
        assertTrue(b.toString().endsWith("novas. que que eu disse"), b.toString());
    }

    @Test
    void emptyTranscriptionIsNotSaved() throws Exception {
        DefaultDictationEngine e = engine(new FakeTranscriber(" "), text -> {});
        e.toggle();
        for (int i = 0; i < 5; i++) mic.push(VadTest.SPEECH);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.ERROR, next());
        assertTrue(history.all().isEmpty());
    }

    // ---------- prévia provisória (modelo leve) ----------

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 500 && !ok.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(ok.getAsBoolean(), "esperou 5 s");
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Texto ao vivo como "confirmado | prévia". */
    List<String> live(DefaultDictationEngine e) {
        List<String> events = new CopyOnWriteArrayList<>();
        e.addListener(new DictationListener() {
            @Override
            public void onLiveText(String confirmed, String provisional) {
                events.add(confirmed + " | " + provisional);
            }
        });
        return events;
    }

    void drainUntil(DictationState last) throws InterruptedException {
        for (DictationState s = next(); s != last; s = next()) assertTrue(s != null, "esperava " + last);
    }

    /** Prévia falsa: devolve "p1", "p2", ... e guarda o tamanho e a thread de cada chamada. */
    static class FakePreview implements Transcriber {
        final List<Integer> samples = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public String transcribe(short[] pcm16k) {
            throw new AssertionError("a prévia também recebe o contexto");
        }

        @Override
        public String transcribe(short[] pcm16k, String previous) {
            samples.add(pcm16k.length);
            threads.add(Thread.currentThread().getName());
            return "p" + samples.size();
        }
    }

    @Test
    void previewShowsUnconfirmedSpeechUntilThePartConfirmsAndNeverReachesThePaste() throws Exception {
        CountDownLatch inSecond = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
        FakePreview fake = new FakePreview() {
            @Override
            public String transcribe(short[] pcm16k, String previous) {
                String r = super.transcribe(pcm16k, previous);
                if (samples.size() == 2) {
                    inSecond.countDown();
                    await(releaseSecond);
                }
                return r;
            }
        };
        preview = fake;
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new PartsTranscriber("Olá mundo.", "fim."), pasted::add);
        List<String> events = live(e);
        e.toggle();
        push(VadTest.SPEECH, 3); // 1ª prévia
        waitFor(() -> events.size() == 1);
        push(VadTest.SPEECH, 3); // 2ª prévia fica presa no modelo leve
        assertTrue(inSecond.await(5, TimeUnit.SECONDS));
        push(VadTest.SPEECH, 1.5);
        push(VadTest.QUIET, 0.7); // pausa: o trecho volta do modelo escolhido
        waitFor(() -> events.size() == 2);
        assertEquals(2, fake.samples.size(), "prévia ocupada: rodadas puladas, sem fila");
        releaseSecond.countDown(); // a 2ª termina depois da confirmação: já não vale
        for (int i = 0; i < 300 && fake.samples.size() < 3; i++) {
            mic.push(VadTest.SPEECH);
            Thread.sleep(5);
        }
        waitFor(() -> events.size() == 3);
        e.toggle();
        drainUntil(DictationState.PASTED);

        assertEquals(List.of(" | p1", "Olá mundo. | ", "Olá mundo. | p3", "Olá mundo. fim. | "), events);
        assertEquals(List.of("Olá mundo. fim."), pasted, "a prévia nunca entra no texto colado");
        assertTrue(fake.samples.get(2) < 3 * Vad.RATE, "só o trecho em andamento: " + fake.samples.get(2));
        assertTrue(fake.threads.stream().allMatch("arara-previa"::equals), "fora do microfone e da transcrição");
    }

    @Test
    void cutPartKeepsItsPreviewAndTheNextPreviewCoversItsEnd() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PartsTranscriber t = new PartsTranscriber("A.", "B.") {
            @Override
            public String transcribe(short[] pcm16k, String previous) {
                if (samples.isEmpty()) await(release);
                return super.transcribe(pcm16k, previous);
            }
        };
        FakePreview fake = new FakePreview();
        preview = fake;
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(t, pasted::add);
        List<String> events = live(e);
        e.toggle();
        push(VadTest.SPEECH, 3);
        waitFor(() -> events.size() == 1);
        push(VadTest.SPEECH, 3);
        waitFor(() -> events.size() == 2);
        push(VadTest.SPEECH, 1); // menos de 2,4 s desde a 2ª prévia: sem prévia nova em A
        push(VadTest.QUIET, 0.7); // pausa: trecho A cortado e preso no modelo escolhido
        push(VadTest.SPEECH, 1); // trecho B em andamento
        waitFor(() -> events.size() == 3);
        release.countDown();
        waitFor(() -> events.size() == 4);
        e.toggle();
        drainUntil(DictationState.PASTED);

        assertEquals(List.of(" | p1", " | p2", " | p2 p3", "A. | p3", "A. B. | "), events,
                "a prévia de A fica até A voltar; a de B vem depois dela");
        assertTrue(fake.samples.get(2) > 3 * Vad.RATE, "a prévia de B começa no fim da prévia de A: " + fake.samples.get(2));
        assertEquals(List.of("A. B."), pasted);
    }

    @Test
    void noPreviewAfterEscOrStop() throws Exception {
        java.util.concurrent.Semaphore entered = new java.util.concurrent.Semaphore(0), allowed = new java.util.concurrent.Semaphore(0);
        preview = new Transcriber() {
            @Override
            public String transcribe(short[] pcm16k) {
                entered.release();
                allowed.acquireUninterruptibly();
                return "tarde demais";
            }
        };
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new FakeTranscriber("texto final"), pasted::add);
        List<String> events = live(e);

        e.toggle();
        push(VadTest.SPEECH, 3);
        assertTrue(entered.tryAcquire(5, TimeUnit.SECONDS));
        e.cancel(); // Esc com a prévia no meio
        allowed.release();
        drainUntil(DictationState.IDLE);
        Thread.sleep(200); // a prévia cancelada termina e libera a vez

        e.toggle();
        push(VadTest.SPEECH, 3);
        assertTrue(entered.tryAcquire(5, TimeUnit.SECONDS));
        e.toggle(); // parou com a prévia no meio
        drainUntil(DictationState.PASTED);
        allowed.release();
        Thread.sleep(200);

        assertEquals(List.of("texto final | "), events, "nenhuma prévia depois do Esc nem da parada");
        assertEquals(List.of("texto final"), pasted);
    }

    @Test
    void failingPreviewTurnsOffWithoutHurtingTheDictation() throws Exception {
        List<Integer> calls = new CopyOnWriteArrayList<>();
        preview = pcm -> {
            calls.add(pcm.length);
            throw new IllegalStateException("modelo leve quebrado");
        };
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new FakeTranscriber("tudo certo"), pasted::add);
        e.toggle();
        push(VadTest.SPEECH, 3);
        waitFor(() -> calls.size() == 1);
        Thread.sleep(50);
        push(VadTest.SPEECH, 3);
        e.toggle();
        drainUntil(DictationState.PASTED);
        assertEquals(1, calls.size(), "prévia desligada neste ditado");
        assertEquals(List.of("tudo certo"), pasted);
    }

    @Test
    void lightModelChosenSkipsTheSeparatePreview() {
        try (WhisperTranscriber.Preview p = new WhisperTranscriber.Preview(dir, () -> Settings.Model.LEVE)) {
            assertNull(p.transcribe(new short[Vad.RATE], ""), "o LEVE já é o modelo escolhido: nem carrega outro");
        }
    }

    @Test
    void holdToTalkReleaseEndsAndSilenceTimeoutDoesNot() throws Exception {
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new FakeTranscriber("oi"), pasted::add);
        e.press();
        e.press(); // repetição: ignorada
        assertEquals(DictationState.LISTENING, next());
        for (int i = 0; i < 4; i++) mic.push(VadTest.SPEECH);
        for (int i = 0; i < 12000; i++) mic.push(VadTest.QUIET); // ~6 min de silêncio: sem TIMEOUT segurando
        assertTrue(mic.onChunk != null, "segue ouvindo");
        e.release();
        while (next() != DictationState.PASTED) {}
        assertEquals(List.of("oi"), pasted);
    }

    @Test
    void holdToTalkShortTapCancelsWithoutPasting() throws Exception {
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(new FakeTranscriber("oi"), pasted::add);
        e.press();
        assertEquals(DictationState.LISTENING, next());
        mic.push(VadTest.QUIET); // menos de 250 ms
        e.release();
        assertEquals(DictationState.IDLE, next());
        assertTrue(pasted.isEmpty());
        assertTrue(errors.isEmpty());
        e.release(); // solta sem ter apertado: nada
    }

    /** Fala de ~9 s (sem corte no meio), transcrita em {@code whisperSec} segundos de relógio controlado. */
    private int slowFlags(double whisperSec) throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        int[] slow = {0};
        DefaultDictationEngine e = engine(new Transcriber() {
            @Override
            public String transcribe(short[] pcm16k) {
                clock.addAndGet((long) (whisperSec * 1e9));
                return "oi";
            }
        }, text -> {});
        e.nanos = clock::get;
        e.addListener(new DictationListener() {
            @Override
            public void onSlowModel() {
                slow[0]++;
            }
        });
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        for (int i = 0; i < 9 * Vad.RATE / VadTest.CHUNK; i++) mic.push(VadTest.SPEECH);
        e.toggle();
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());
        return slow[0];
    }

    @Test
    void avisaModeloLentoSoComRtfAlto() throws Exception {
        assertEquals(0, slowFlags(3), "RTF 0,33: sem aviso");
        assertEquals(1, slowFlags(9.5), "RTF 1,05: avisa uma vez");
    }

    @Test
    void estimaSegundosQueFaltam() {
        assertEquals(0, DefaultDictationEngine.estimate(5 * Vad.RATE, 0, 0), "sem RTF ainda: sem número");
        assertEquals(8, DefaultDictationEngine.estimate(10 * Vad.RATE, 4, 5), 1e-9); // 10 s pendentes x RTF 0,8
        assertEquals(0, DefaultDictationEngine.estimate(0, 4, 5), 1e-9);
    }
}
