package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelDownloadTest {
    static final byte[] MODEL = "modelo falso".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;
    HttpServer server;
    URI url;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/m.bin", ex -> {
            ex.sendResponseHeaders(200, MODEL.length);
            ex.getResponseBody().write(MODEL);
            ex.close();
        });
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/m.bin");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void downloadsAndChecksHash() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MODEL));
        Path target = dir.resolve("models").resolve("m.bin");
        AtomicReference<Double> last = new AtomicReference<>();
        WhisperTranscriber.download(url, sha, target, last::set);
        assertArrayEquals(MODEL, Files.readAllBytes(target));
        assertEquals(1.0, last.get());
    }

    @Test
    void wrongHashLeavesNoModel() {
        Path target = dir.resolve("m.bin");
        assertThrows(IOException.class, () -> WhisperTranscriber.download(url, "00", target, p -> {}));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("m.bin.part")));
    }
}
