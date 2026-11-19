package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.givimad.whisperjni.WhisperJNI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #83: whisper.dll alheio no PATH não pode ser usado no lugar das DLLs do pacote. */
class WhisperPathTest {
    @Test
    void carregaMesmoComWhisperDllFalsaNoPath(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        Path fake = Files.createDirectory(tmp.resolve("whisper.cpp"));
        for (String n : new String[] {"whisper.dll", "ggml.dll", "ggml-base.dll"}) Files.writeString(fake.resolve(n), "nao sou uma dll");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Filho.class.getName());
        pb.environment().put("PATH", fake + ";" + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    public static final class Filho {
        public static void main(String[] a) throws Exception {
            WhisperTranscriber.loadNative();
            new WhisperJNI();
        }
    }
}
