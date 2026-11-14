package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    final List<Integer> ranges = new CopyOnWriteArrayList<>();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/m.bin", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            int from = range == null ? 0 : Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            ranges.add(from);
            if (from > 0) ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (MODEL.length - 1) + "/" + MODEL.length);
            ex.sendResponseHeaders(from > 0 ? 206 : 200, MODEL.length - from);
            ex.getResponseBody().write(MODEL, from, MODEL.length - from);
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
        AtomicReference<long[]> last = new AtomicReference<>();
        assertTrue(WhisperTranscriber.download(url, sha, target, (d, t) -> last.set(new long[] {d, t}), () -> false));
        assertArrayEquals(MODEL, Files.readAllBytes(target));
        assertArrayEquals(new long[] {MODEL.length, MODEL.length}, last.get());
    }

    @Test
    void resumesFromPartAfterConnectionDrop() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MODEL));
        Path target = dir.resolve("m.bin");
        Files.write(dir.resolve("m.bin.part"), Arrays.copyOf(MODEL, 5)); // a internet caiu depois de 5 bytes
        List<Long> seen = new ArrayList<>();
        WhisperTranscriber.download(url, sha, target, (d, t) -> seen.add(d), () -> false);
        assertEquals(List.of(5), ranges, "pediu só o resto");
        assertEquals(5L, seen.get(0));
        assertArrayEquals(MODEL, Files.readAllBytes(target));
    }

    @Test
    void cancelKeepsPartForLater() throws Exception {
        Path target = dir.resolve("m.bin");
        assertFalse(WhisperTranscriber.download(url, "00", target, (d, t) -> {}, () -> true));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(dir.resolve("m.bin.part")));
    }

    @Test
    void wrongHashLeavesNoModel() {
        Path target = dir.resolve("m.bin");
        assertThrows(IOException.class, () -> WhisperTranscriber.download(url, "00", target, (d, t) -> {}, () -> false));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("m.bin.part")));
    }
}
