package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ditado chamado por voz ("Arara"): fim por "Pronto" ou silêncio e aprovação por "Aprovado". */
class VoiceDictationTest {
    @TempDir
    Path dir;

    final DefaultDictationEngineTest.FakeMic mic = new DefaultDictationEngineTest.FakeMic();
    final BlockingQueue<DictationState> states = new LinkedBlockingQueue<>();
    final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
    final List<String> pasted = new CopyOnWriteArrayList<>();
    final AtomicInteger checks = new AtomicInteger();
    HistoryStore history;
    SettingsStore settings;
    /** O que o reconhecedor de comandos "ouve" em cada trecho curto. */
    volatile VoiceCommand heard;

    DefaultDictationEngine engine(String transcribed, boolean approval) {
        history = new JsonlHistoryStore(dir.resolve("historico.jsonl"));
        settings = new JsonSettingsStore(dir.resolve("config.json"));
        settings.save(Settings.defaults().withVoiceApproval(approval));
        DefaultDictationEngine e = new DefaultDictationEngine(history, new JsonDictionaryStore(dir.resolve("dic.json")),
                new DefaultDictationEngineTest.FakeTranscriber(transcribed), mic, pasted::add);
        e.setVoice(settings, pcm -> {
            checks.incrementAndGet();
            return heard;
        });
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

    /** Empurra blocos até o tempo pedido ou até o motor soltar o microfone (o fim por voz é assíncrono). */
    void push(short[] chunk, double seconds) throws InterruptedException {
        for (long n = 0; n < seconds * Vad.RATE; n += VadTest.CHUNK) {
            Consumer<short[]> c = mic.onChunk;
            if (c == null) return;
            c.accept(chunk);
            Thread.sleep(1); // dá tempo da checagem do "Pronto", que roda em outra thread
        }
    }

    void waitFor(DictationState wanted) throws InterruptedException {
        for (DictationState s = next(); s != wanted; s = next()) {
            if (s == null) throw new AssertionError("não chegou em " + wanted);
        }
    }

    @Test
    void prontoEncerraEFicaForaDoTexto() throws Exception {
        DefaultDictationEngine e = engine("Oi, Davi. Pronto.", false);
        e.startByVoice();
        assertEquals(DictationState.LISTENING, next());
        push(VadTest.SPEECH, 2);   // fala longa: não é comando, nem passa pelo reconhecedor
        push(VadTest.QUIET, 0.8);
        assertEquals(0, checks.get());
        heard = VoiceCommand.PRONTO;
        push(VadTest.SPEECH, 0.5); // "Pronto" sozinho
        push(VadTest.QUIET, 1.5);
        waitFor(DictationState.PASTED);
        assertEquals(List.of("Oi, Davi."), pasted);
        assertEquals("Oi, Davi.", history.all().get(0).texto());
    }

    @Test
    void prontoReconhecidoTardeAindaSaiDoTexto() throws Exception {
        // O "Pronto" às vezes demora no Whisper e os 4 s de silêncio encerram antes: a palavra sai do texto mesmo assim.
        DefaultDictationEngine e = engine("Oi, Davi. Pronto?", false);
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.PASTED);
        assertEquals(List.of("Oi, Davi."), pasted);
    }

    @Test
    void quatroSegundosDeSilencioEncerramOChamadoPorVoz() throws Exception {
        DefaultDictationEngine e = engine("oi", false);
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.PASTED);
        assertEquals(List.of("oi"), pasted);
    }

    @Test
    void peloAtalhoOSilencioNaoEncerra() throws Exception {
        DefaultDictationEngine e = engine("oi", false);
        e.toggle();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        assertEquals(DictationState.LISTENING, next());
        assertEquals(DictationState.SILENCE, next());
        assertEquals(null, states.poll(300, TimeUnit.MILLISECONDS), "continua ouvindo até o atalho");
        assertEquals(0, checks.get(), "\"Pronto\" só vale no chamado por voz");
    }

    @Test
    void semFalaDepoisDoChamadoDesiste() throws Exception {
        DefaultDictationEngine e = engine("oi", false);
        e.startByVoice();
        push(VadTest.QUIET, 6.5);
        waitFor(DictationState.ERROR);
        assertEquals("Não detectei fala.", errors.poll(5, TimeUnit.SECONDS));
        assertTrue(pasted.isEmpty());
    }

    @Test
    void aprovadoCola() throws Exception {
        DefaultDictationEngine e = engine("texto para aprovar", true);
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.AWAITING_APPROVAL);
        assertEquals("texto para aprovar", history.all().get(0).texto(), "já salvo antes da aprovação");
        assertTrue(pasted.isEmpty(), "ainda não colou");
        e.approve();
        waitFor(DictationState.PASTED);
        assertEquals(List.of("texto para aprovar"), pasted);
    }

    @Test
    void cancelaNaoColaEGuardaNoHistorico() throws Exception {
        DefaultDictationEngine e = engine("não quero", true);
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.AWAITING_APPROVAL);
        e.cancel();
        waitFor(DictationState.ERROR);
        assertEquals("Não colei. O texto está no histórico.", errors.poll(5, TimeUnit.SECONDS));
        assertTrue(pasted.isEmpty());
        assertEquals("não quero", history.all().get(0).texto());
    }

    @Test
    void semRespostaNoPrazoNaoCola() throws Exception {
        DefaultDictationEngine e = engine("esqueci", true);
        e.approvalTimeoutMs = 200;
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.AWAITING_APPROVAL);
        waitFor(DictationState.ERROR);
        assertEquals("Não colei. O texto está no histórico.", errors.poll(5, TimeUnit.SECONDS));
        assertTrue(pasted.isEmpty());
        e.approve(); // tarde demais: não faz nada
        assertTrue(pasted.isEmpty());
    }

    @Test
    void atalhoNaEsperaAprova() throws Exception {
        DefaultDictationEngine e = engine("pelo atalho", true);
        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.AWAITING_APPROVAL);
        e.toggle();
        waitFor(DictationState.PASTED);
        assertEquals(List.of("pelo atalho"), pasted);

        e.startByVoice();
        push(VadTest.SPEECH, 2);
        push(VadTest.QUIET, 4.5);
        waitFor(DictationState.AWAITING_APPROVAL);
        e.press(); // segurar para falar: apertar também aprova, não começa outro ditado
        waitFor(DictationState.PASTED);
        assertEquals(2, pasted.size());
    }

    @Test
    void ditadoPeloAtalhoColaDiretoMesmoComAprovacaoLigada() throws Exception {
        DefaultDictationEngine e = engine("direto", true);
        e.toggle();
        push(VadTest.SPEECH, 1);
        e.toggle();
        waitFor(DictationState.PASTED);
        assertEquals(List.of("direto"), pasted);
    }

    @Test
    void chamadoPorVozSoComOMotorParado() throws Exception {
        DefaultDictationEngine e = engine("oi", false);
        e.toggle();
        assertEquals(DictationState.LISTENING, next());
        e.startByVoice(); // já está ouvindo: ignora
        assertEquals(null, states.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void tiraOProntoDoFimDoTexto() {
        assertEquals("Oi, Davi.", DefaultDictationEngine.stripPronto("Oi, Davi. Pronto."));
        assertEquals("Oi, Davi", DefaultDictationEngine.stripPronto("Oi, Davi, pronto"));
        assertEquals("Está pronto o relatório.", DefaultDictationEngine.stripPronto("Está pronto o relatório."));
        assertEquals("", DefaultDictationEngine.stripPronto("Pronto."));
    }
}
