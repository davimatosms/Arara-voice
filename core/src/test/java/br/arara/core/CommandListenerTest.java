package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ouvinte dos comandos de voz fora do ditado: "Arara" para chamar, "Aprovado" e "Cancela" na espera. */
class CommandListenerTest {
    /** Motor falso: só guarda o estado e o que foi pedido. */
    static class FakeEngine implements DictationEngine {
        volatile DictationState state = DictationState.IDLE;
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override public void toggle() { calls.add("toggle"); }
        @Override public void startByVoice() { calls.add("voz"); }
        @Override public void approve() { calls.add("aprovar"); }
        @Override public void cancel() { calls.add("cancelar"); }
        @Override public DictationState state() { return state; }
        @Override public void addListener(DictationListener listener) {}
        @Override public void removeListener(DictationListener listener) {}
        @Override public void close() {}
    }

    final DefaultDictationEngineTest.FakeMic mic = new DefaultDictationEngineTest.FakeMic();
    final FakeEngine engine = new FakeEngine();
    final AtomicInteger recognitions = new AtomicInteger();
    volatile VoiceCommand heard = VoiceCommand.ARARA;
    volatile boolean wakeOn = true;
    volatile CountDownLatch gate; // segura o reconhecedor (teste de "um por vez")
    CommandListener listener;

    CommandListener listener() {
        listener = new CommandListener(engine, mic, pcm -> {
            recognitions.incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return heard;
        }, () -> wakeOn);
        return listener;
    }

    @AfterEach
    void close() {
        if (listener != null) listener.close();
    }

    void push(short[] chunk, double seconds) {
        for (long n = 0; n < seconds * Vad.RATE; n += VadTest.CHUNK) {
            Consumer<short[]> c = mic.onChunk;
            if (c == null) return;
            c.accept(chunk);
        }
    }

    /** "Arara" falado: pausa, trecho curto, pausa. */
    void shortWord() {
        push(VadTest.QUIET, 0.6);
        push(VadTest.SPEECH, 0.6);
        push(VadTest.QUIET, 0.8);
    }

    static void eventually(Runnable check) throws InterruptedException {
        AssertionError last = null;
        for (int i = 0; i < 200; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(10);
            }
        }
        throw last;
    }

    @Test
    void araraChamaODitadoESoltaOMicrofoneAntes() throws Exception {
        listener().sync();
        assertNotNull(mic.onChunk, "parado e com o chamado ligado: ouvindo");
        shortWord();
        eventually(() -> assertEquals(List.of("voz"), engine.calls));
        assertNull(mic.onChunk, "solta o microfone para o ditado");
    }

    @Test
    void falaLongaNemPassaPeloWhisper() throws Exception {
        listener().sync();
        push(VadTest.QUIET, 0.6);
        push(VadTest.SPEECH, 3);
        push(VadTest.QUIET, 1);
        Thread.sleep(100);
        assertEquals(0, recognitions.get());
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void chamadoDesligadoNaoAbreOMicrofoneParado() {
        wakeOn = false;
        listener().sync();
        assertNull(mic.onChunk);
    }

    @Test
    void naEsperaOuveAprovadoECancelaMesmoComOChamadoDesligado() throws Exception {
        wakeOn = false;
        engine.state = DictationState.AWAITING_APPROVAL;
        listener().sync();
        heard = VoiceCommand.APROVADO;
        shortWord();
        eventually(() -> assertEquals(List.of("aprovar"), engine.calls));

        listener.sync(); // ainda esperando (o motor falso não muda de estado)
        heard = VoiceCommand.CANCELA;
        shortWord();
        eventually(() -> assertEquals(List.of("aprovar", "cancelar"), engine.calls));
    }

    @Test
    void comandoForaDeHoraEIgnorado() throws Exception {
        listener().sync();
        heard = VoiceCommand.APROVADO; // parado: nada a aprovar
        shortWord();
        eventually(() -> assertEquals(1, recognitions.get()));
        Thread.sleep(50);
        assertEquals(List.of(), engine.calls);
    }

    @Test
    void soltaOMicrofoneEnquantoOMotorGrava() {
        listener().sync();
        assertNotNull(mic.onChunk);
        engine.state = DictationState.LISTENING;
        listener.onState(DictationState.LISTENING);
        assertNull(mic.onChunk);
        engine.state = DictationState.IDLE;
        listener.onState(DictationState.IDLE);
        assertNotNull(mic.onChunk, "volta a ouvir o chamado");
    }

    @Test
    void umReconhecimentoPorVez() throws Exception {
        gate = new CountDownLatch(1);
        heard = null;
        listener().sync();
        shortWord();
        eventually(() -> assertEquals(1, recognitions.get()));
        shortWord(); // chega com o primeiro ainda no Whisper: descartado
        Thread.sleep(50);
        assertEquals(1, recognitions.get());
        gate.countDown();
    }
}
