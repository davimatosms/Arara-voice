package br.arara.app;

import br.arara.core.AraraCore;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prepara o modelo de voz numa thread e guarda o estado para a UI: bytes, MB/s, tempo restante.
 * Se a internet cair, pausa e tenta de novo a cada 10 s (o core continua o download do ponto onde parou).
 * Estado e ouvintes só na EDT.
 */
final class ModelDownload {
    enum Phase { LOADING, DOWNLOADING, PAUSED, READY, FAILED }

    /** O trabalho de verdade: {@code AraraCore::prepareModel}. */
    interface Task {
        void run(AraraCore.DownloadProgress progress) throws IOException;
    }

    private static final int RETRY_MS = 10_000;
    private static final long POST_EVERY = 100_000_000L; // 100 ms entre atualizações da tela

    private final Task task;
    private final List<Runnable> listeners = new ArrayList<>();
    private final Timer retry = new Timer(RETRY_MS, e -> start());
    private final Rate rate = new Rate();
    Phase phase = Phase.LOADING;
    long done, total = -1;
    /** Bytes por segundo, média dos últimos 5 s. */
    double speed;
    private boolean running, again;
    private volatile long lastPost;

    ModelDownload(Task task) {
        this.task = task;
        retry.setRepeats(false);
    }

    void addListener(Runnable r) {
        listeners.add(r);
    }

    /** Começa (ou recomeça, depois de trocar o modelo ou de cair a internet). */
    void start() {
        retry.stop();
        if (running) {
            again = true; // termina o que está fazendo e roda de novo com o modelo novo
            return;
        }
        running = true;
        again = false;
        phase = done > 0 && phase == Phase.PAUSED ? Phase.DOWNLOADING : Phase.LOADING;
        fire();
        Thread t = new Thread(() -> {
            Phase end;
            try {
                task.run(this::progress);
                end = Phase.READY;
            } catch (IOException e) {
                end = Phase.PAUSED;
            } catch (RuntimeException | LinkageError e) {
                end = Phase.FAILED;
            }
            Phase result = end;
            SwingUtilities.invokeLater(() -> finished(result));
        }, "arara-modelo");
        t.setDaemon(true);
        t.start();
    }

    /** "Tentar agora" do erro sem internet. */
    void retryNow() {
        if (phase == Phase.PAUSED || phase == Phase.FAILED) start();
    }

    double fraction() {
        return phase == Phase.READY ? 1 : total > 0 ? (double) done / total : 0;
    }

    /** Chamado na thread do download a cada bloco; só repassa para a tela a cada 100 ms. */
    private void progress(long d, long t) {
        long now = System.nanoTime();
        if (now - lastPost < POST_EVERY && d != t) return;
        lastPost = now;
        SwingUtilities.invokeLater(() -> {
            if (d < done) rate.reset(); // trocou de modelo ou recomeçou do zero
            done = d;
            total = t;
            speed = rate.add(now, d);
            phase = Phase.DOWNLOADING;
            fire();
        });
    }

    private void finished(Phase end) {
        running = false;
        if (again) {
            start();
            return;
        }
        phase = end;
        if (end == Phase.READY) done = total;
        if (end == Phase.PAUSED) {
            speed = 0;
            rate.reset();
            retry.start();
        }
        fire();
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    /** "356 de 574 MB" (MB decimais, como o tamanho que o site mostra). */
    static String mb(long done, long total) {
        return total > 0 ? Math.round(done / 1e6) + " de " + Math.round(total / 1e6) + " MB" : Math.round(done / 1e6) + " MB";
    }

    /** "8,4 MB/s · faltam 26 s". */
    static String detail(double bytesPerSecond, long done, long total) {
        if (bytesPerSecond <= 0 || total <= 0) return "calculando a velocidade…";
        long left = Math.round((total - done) / bytesPerSecond);
        String time = left < 60 ? left + " s" : left < 3600 ? Math.round(left / 60.0) + " min" : (left / 3600) + " h";
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB/s · faltam %s", bytesPerSecond / 1e6, time);
    }

    /** Velocidade média numa janela de 5 s, para o número não pular a cada bloco. */
    static final class Rate {
        private static final long WINDOW = 5_000_000_000L;
        private final ArrayDeque<long[]> samples = new ArrayDeque<>();

        /** @return bytes por segundo entre a amostra mais antiga da janela e esta */
        double add(long nanos, long bytes) {
            samples.addLast(new long[] {nanos, bytes});
            while (samples.size() > 2 && nanos - samples.peekFirst()[0] > WINDOW) samples.removeFirst();
            long[] first = samples.peekFirst();
            return nanos == first[0] ? 0 : (bytes - first[1]) * 1e9 / (nanos - first[0]);
        }

        void reset() {
            samples.clear();
        }
    }
}
