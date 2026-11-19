package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrecisoMigracaoTest {
    private static final String NOVO = "ggml-large-v3-turbo-q8_0.bin";

    private static WhisperTranscriber make(Path dir) {
        WhisperTranscriber w = new WhisperTranscriber(dir, () -> Settings.Model.PRECISO);
        w.background = Runnable::run;
        return w;
    }

    @Test
    void comAntigoUsaAntigoEAgendaDownload(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(WhisperTranscriber.OLD_PRECISO), "x");
        WhisperTranscriber w = make(dir);
        assertEquals(WhisperTranscriber.OLD_PRECISO, w.fileFor(Settings.Model.PRECISO).getFileName().toString());
        AtomicInteger calls = new AtomicInteger();
        w.downloader = (u, sha, target, p, c) -> {
            calls.incrementAndGet();
            assertTrue(u.toString().endsWith(NOVO));
            Files.writeString(target, "y");
            return true;
        };
        java.lang.reflect.Method m = WhisperTranscriber.class.getDeclaredMethod("upgradeInBackground");
        m.setAccessible(true);
        m.invoke(w);
        assertEquals(1, calls.get());
        assertEquals(NOVO, w.fileFor(Settings.Model.PRECISO).getFileName().toString());
        assertTrue(Files.exists(dir.resolve(WhisperTranscriber.OLD_PRECISO))); // só some depois de carregar o novo
    }

    @Test
    void falhaDeDownloadMantemAntigo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(WhisperTranscriber.OLD_PRECISO), "x");
        WhisperTranscriber w = make(dir);
        w.downloader = (u, sha, target, p, c) -> {
            throw new IOException("sem rede");
        };
        java.lang.reflect.Method m = WhisperTranscriber.class.getDeclaredMethod("upgradeInBackground");
        m.setAccessible(true);
        m.invoke(w);
        assertEquals(WhisperTranscriber.OLD_PRECISO, w.fileFor(Settings.Model.PRECISO).getFileName().toString());
        assertTrue(Files.exists(dir.resolve(WhisperTranscriber.OLD_PRECISO)));
    }

    @Test
    void comNovoPresenteUsaNovo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(WhisperTranscriber.OLD_PRECISO), "x");
        Files.writeString(dir.resolve(NOVO), "y");
        assertEquals(NOVO, make(dir).fileFor(Settings.Model.PRECISO).getFileName().toString());
    }
}
