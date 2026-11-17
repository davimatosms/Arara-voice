package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Usuário do Windows com acento no nome (C:\Users\João): o modelo precisa carregar. Precisa de ARARA_TEST_MODEL (ggml-base). */
class CaminhoComAcentoTest {
    @Test
    void carregaModeloEmPastaComAcento(@TempDir Path tmp) throws Exception {
        String model = System.getenv("ARARA_TEST_MODEL");
        Assumptions.assumeTrue(model != null && Files.exists(Path.of(model)), "sem ARARA_TEST_MODEL");
        for (String nome : new String[] {"sem-acento", "João-ção"}) {
            Path dir = Files.createDirectories(tmp.resolve(nome).resolve("models"));
            Files.copy(Path.of(model), dir.resolve("ggml-base.bin"));
            try (WhisperTranscriber t = new WhisperTranscriber(dir, () -> Settings.Model.LEVE)) {
                t.prepare((d, total) -> {});
            } catch (Throwable e) {
                fail(nome + ": " + e, e);
            }
        }
    }

    @Test
    void modeloInvalidoDaErroComCausa(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("ggml-base.bin"), new byte[4096]);
        try (WhisperTranscriber t = new WhisperTranscriber(dir, () -> Settings.Model.LEVE)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> t.prepare((d, total) -> {}));
            assertTrue(e.getMessage().contains("ggml-base.bin"), e.getMessage());
        }
    }
}
