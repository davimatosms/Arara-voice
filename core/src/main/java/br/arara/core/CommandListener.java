package br.arara.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Ouve os comandos de voz fora do ditado: "Arara" com o motor parado (se o chamado por voz está ligado) e
 * "Aprovado"/"Cancela" enquanto um texto espera aprovação. Fica com o microfone aberto, mas só manda ao Whisper trechos
 * curtos e isolados (0,25 a 2 s entre pausas): conversa e reunião passam direto, sem custo de CPU. Solta o microfone
 * enquanto o motor grava (o ditado tem o dele) e o retoma quando o motor para.
 */
final class CommandListener implements DictationListener, AutoCloseable {
    /** Diagnóstico (nível FINE, desligado por padrão): abrir/fechar o microfone, trechos e o que o Whisper ouviu. */
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("br.arara.voz");
    static final int MIN = Vad.RATE / 4, MAX = Vad.RATE * 2;
    /** Pausa que fecha um trecho (mais curta que a do ditado: comando é palavra solta). */
    static final int PAUSE = Vad.RATE / 2;
    /** Áudio guardado, em amostras: cobre o trecho mais longo com folga. */
    private static final int KEEP = Vad.RATE * 3;
    private static final int MARGIN = Vad.RATE / 5;

    private final DictationEngine engine;
    private final DefaultDictationEngine.AudioSource mic;
    private final VoiceCommandRecognizer recognizer;
    private final BooleanSupplier wakeOn;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-ouvido");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "arara-ouvido-config");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(); // um reconhecimento por vez; os outros trechos são descartados
    private final short[] ring = new short[KEEP];
    private boolean listening;
    private Vad vad;
    private long fed;
    private long handled = -1; // início do último trecho já avaliado
    private boolean warmed; // modelo leve já pedido uma vez (o primeiro "Arara" não se perde carregando o modelo)

    CommandListener(DictationEngine engine, DefaultDictationEngine.AudioSource mic, VoiceCommandRecognizer recognizer,
            BooleanSupplier wakeOn) {
        this.engine = engine;
        this.mic = mic;
        this.recognizer = recognizer;
        this.wakeOn = wakeOn;
    }

    /** Confere a configuração a cada segundo: ligar "Chamar pela voz" nas Configurações já vale, sem reabrir o app. */
    void start() {
        poll.scheduleWithFixedDelay(this::sync, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void onState(DictationState s) {
        sync(s); // usa o estado recebido: o motor chama isto segurando a trava dele
    }

    /** Abre ou fecha o microfone conforme o estado do motor e a configuração. */
    void sync() {
        sync(engine.state()); // consulta o motor fora da trava deste ouvinte (sem deadlock com o onState)
    }

    private synchronized void sync(DictationState s) {
        boolean want = s == DictationState.AWAITING_APPROVAL || (idle(s) && wakeOn.getAsBoolean());
        if (want && !listening) open();
        else if (!want && listening) closeMic();
    }

    static boolean idle(DictationState s) {
        return s == DictationState.IDLE || s == DictationState.PASTED || s == DictationState.ERROR;
    }

    private void open() {
        vad = new Vad();
        fed = 0;
        handled = -1;
        listening = true;
        if (!warmed) {
            warmed = true;
            // Na fila do mesmo executor: um comando que chegue durante o aquecimento espera por ele, não se perde.
            worker.execute(() -> {
                try {
                    recognizer.warmUp();
                } catch (RuntimeException e) { // sem modelo ainda: o próximo trecho tenta de novo
                }
            });
        }
        try {
            mic.start(this::onChunk);
            LOG.fine("ouvinte: microfone aberto");
        } catch (Exception e) {
            listening = false; // microfone ocupado ou desligado: tenta de novo no próximo sync
            LOG.log(java.util.logging.Level.INFO, "ouvinte: não abriu o microfone", e);
        }
    }

    private void closeMic() {
        listening = false;
        mic.stop();
        LOG.fine("ouvinte: microfone fechado");
    }

    private synchronized void onChunk(short[] chunk) {
        if (!listening) return;
        for (short s : chunk) ring[(int) (fed++ % KEEP)] = s;
        vad.feed(chunk);
        long burst = vad.burstStart(), end = vad.speechEnd();
        if (burst < 0 || burst == handled || fed - end < PAUSE) return;
        handled = burst; // cada trecho uma vez só
        long from = Math.max(0, burst - MARGIN), to = Math.min(fed, end + MARGIN);
        if (end - burst < MIN || end - burst > MAX || fed - from > KEEP) return; // fala normal: nem vai ao Whisper
        if (!busy.compareAndSet(false, true)) {
            LOG.fine(() -> String.format("ouvinte: trecho de %.2f s descartado (outro ainda no Whisper)", (end - burst) / 16000.0));
            return;
        }
        short[] pcm = new short[(int) (to - from)];
        for (int i = 0; i < pcm.length; i++) pcm[i] = ring[(int) ((from + i) % KEEP)];
        worker.execute(() -> {
            try {
                dispatch(recognizer.recognize(pcm));
            } catch (RuntimeException e) { // comando perdido: a pessoa repete
            } finally {
                busy.set(false);
            }
        });
    }

    /** Leva o comando ao motor, se ele faz sentido no estado atual. */
    private void dispatch(VoiceCommand c) {
        DictationState s = engine.state();
        LOG.fine(() -> "ouvinte: comando " + c + " com o motor em " + s);
        if (c == null) return;
        if (c == VoiceCommand.ARARA && idle(s)) {
            synchronized (this) {
                closeMic(); // solta antes: o ditado abre o microfone dele
            }
            engine.startByVoice();
        } else if (s == DictationState.AWAITING_APPROVAL && c == VoiceCommand.APROVADO) {
            engine.approve();
        } else if (s == DictationState.AWAITING_APPROVAL && c == VoiceCommand.CANCELA) {
            engine.cancel();
        }
    }

    /** Há um trecho no Whisper agora (o e2e espera terminar). */
    boolean recognizing() {
        return busy.get();
    }

    @Override
    public void close() {
        poll.shutdownNow();
        worker.shutdownNow();
        synchronized (this) {
            if (listening) closeMic();
        }
    }
}
