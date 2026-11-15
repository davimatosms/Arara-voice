package br.arara.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Uma cópia do Arara por pasta de dados: quem pega o lock de {@code arara.lock} fica; as outras avisam essa
 * para mostrar a janela e saem. O SO solta o lock se o processo morrer. O aviso vai por uma porta só em
 * 127.0.0.1, escrita em {@code arara.porta}, e só aceita a mensagem fixa {@link #SHOW}.
 */
final class SingleInstance implements AutoCloseable {
    static final String HELLO = "ARARA\n";
    static final String SHOW = "MOSTRAR\n";

    private final FileLock lock;
    private final ServerSocket server;
    /** O que fazer quando outra cópia pede para mostrar a janela (roda na thread do socket). */
    volatile Runnable onShow = () -> {};

    private SingleInstance(FileLock lock, ServerSocket server) {
        this.lock = lock;
        this.server = server;
    }

    /** Pega o lock e começa a ouvir; devolve null se outra cópia já tem o lock desta pasta. */
    static SingleInstance acquire(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        FileChannel channel = FileChannel.open(dataDir.resolve("arara.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) { // a mesma JVM já tem
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        SingleInstance instance = new SingleInstance(lock, server);
        // quem lê no meio da escrita vê um número inválido e tenta de novo (notifyFirst)
        Files.writeString(dataDir.resolve("arara.porta"), Integer.toString(server.getLocalPort()));
        Thread t = new Thread(instance::serve, "arara-instancia");
        t.setDaemon(true);
        t.start();
        return instance;
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                if (!s.getInetAddress().isLoopbackAddress()) continue;
                s.setSoTimeout(2000);
                s.getOutputStream().write(HELLO.getBytes(StandardCharsets.US_ASCII));
                if (Arrays.equals(s.getInputStream().readNBytes(SHOW.length()), SHOW.getBytes(StandardCharsets.US_ASCII)))
                    onShow.run();
            } catch (IOException ignored) {
                // conexão ruim ou servidor fechado: segue (o while confere)
            }
        }
    }

    /**
     * Avisa a cópia aberta para mostrar a janela. Tenta por até ~3 s, porque a outra pode ter acabado de pegar
     * o lock e ainda não ter escrito a porta. Devolve false se não conseguiu.
     */
    static boolean notifyFirst(Path dataDir) {
        for (int i = 0; i < 30; i++) {
            try {
                int port = Integer.parseInt(Files.readString(dataDir.resolve("arara.porta")).trim());
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                    s.setSoTimeout(2000);
                    // a saudação confirma que é o Arara, e não um arquivo de porta velho apontando para outro programa
                    if (Arrays.equals(s.getInputStream().readNBytes(HELLO.length()), HELLO.getBytes(StandardCharsets.US_ASCII))) {
                        OutputStream out = s.getOutputStream();
                        out.write(SHOW.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        return true;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // ainda sem porta, ou porta velha: tenta de novo
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        server.close();
        lock.release();
        lock.channel().close();
    }
}
