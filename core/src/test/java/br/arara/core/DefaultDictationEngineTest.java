package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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

    DefaultDictationEngine engine(Transcriber t, DefaultDictationEngine.Paster paster) {
        history = new JsonlHistoryStore(dir.resolve("historico.jsonl"));
        dictionary = new JsonDictionaryStore(dir.resolve("dicionario.json"));
        DefaultDictationEngine e = new DefaultDictationEngine(history, dictionary, t, mic, paster);
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
    void longDictationIsTranscribedInPartsAtPausesAndJoinedInOrder() throws Exception {
        List<String> pasted = new ArrayList<>();
        PartsTranscriber t = new PartsTranscriber("Primeira frase.", "frase. Segunda frase,", "e o fim.");
        DefaultDictationEngine e = engine(t, pasted::add);
        e.toggle();
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.7); // pausa: corta aqui
        push(VadTest.SPEECH, 5);
        push(VadTest.QUIET, 0.3); // curta demais: não corta
        push(VadTest.SPEECH, 0.5);
        push(VadTest.QUIET, 0.7);
        push(VadTest.SPEECH, 1); // menos de 4 s de fala desde o último corte: fica para o fim
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next(), "sem piscar estado durante a fala");
        assertEquals(DictationState.PASTED, next());

        assertEquals(3, t.samples.size());
        assertEquals(List.of("", "Primeira frase.", "Primeira frase. Segunda frase,"), t.contexts);
        assertEquals(List.of("Primeira frase. Segunda frase, e o fim."), pasted, "palavra repetida na fronteira some");
        assertTrue(t.threads.stream().allMatch("arara-transcricao"::equals), "fora da thread do microfone");
    }

    @Test
    void speechWithoutPauseIsCutAtMaxLength() throws Exception {
        PartsTranscriber t = new PartsTranscriber("um", "dois");
        List<String> pasted = new ArrayList<>();
        DefaultDictationEngine e = engine(t, pasted::add);
        e.toggle();
        for (int i = 0; i < 22 * Vad.RATE / VadTest.CHUNK; i++) mic.push(i % 6 == 5 ? VadTest.QUIET : VadTest.SPEECH); // fala sem pausa de frase
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());
        assertEquals(DefaultDictationEngine.MAX_PART, t.samples.get(0));
        assertEquals(2, t.samples.size());
        assertEquals(List.of("um dois"), pasted);
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
}
