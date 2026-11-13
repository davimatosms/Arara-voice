package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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
        mic.push(VadTest.SPEECH);
        mic.push(VadTest.SPEECH);
        e.toggle();
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());

        assertEquals(1024, t.samples);
        assertEquals(List.of("oi Davi"), pasted);
        assertEquals("oi Davi", history.all().get(0).texto());
        assertNull(mic.onChunk, "microfone desligado");
    }

    @Test
    void pasteFailureKeepsTextInHistory() throws Exception {
        DefaultDictationEngine e = engine(new FakeTranscriber("texto"), text -> {
            throw new IllegalStateException("clipboard ocupado");
        });
        e.toggle();
        mic.push(VadTest.SPEECH);
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
        for (long n = 0; n <= 5L * 60 * Vad.RATE && mic.onChunk != null; n += VadTest.CHUNK) mic.push(VadTest.QUIET);
        assertEquals(DictationState.SILENCE, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.PASTED, next());
        assertEquals(List.of("o que deu tempo de falar"), pasted);
        assertFalse(history.all().isEmpty());
    }

    @Test
    void emptyTranscriptionIsNotSaved() throws Exception {
        DefaultDictationEngine e = engine(new FakeTranscriber(" "), text -> {});
        e.toggle();
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.TRANSCRIBING, next());
        assertEquals(DictationState.ERROR, next());
        assertTrue(history.all().isEmpty());
    }
}
