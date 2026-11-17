package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuTest {
    @Test
    void marcadorPresenteDesligaGpuEAvisa(@TempDir Path dir) throws Exception {
        JsonSettingsStore store = new JsonSettingsStore(dir.resolve("config.json"));
        store.save(Settings.defaults().withAccel(Settings.Accel.AUTO));
        Files.writeString(dir.resolve(Gpu.MARKER), "1");
        assertEquals(Gpu.NOTICE, Gpu.recover(dir, store));
        assertEquals(Settings.Accel.CPU_ONLY, store.load().accel());
        assertFalse(Files.exists(dir.resolve(Gpu.MARKER)));
        assertNull(Gpu.recover(dir, store)); // aviso só uma vez
    }

    @Test
    void escolhaDeAceleracao() {
        Settings antigo = Settings.defaults(); // CPU (config antigo)
        assertTrue(Gpu.enabled(antigo, true, false));
        assertFalse(Gpu.enabled(antigo, true, true)); // M1: padrão processador
        assertTrue(Gpu.enabled(antigo.withAccel(Settings.Accel.AUTO), true, true)); // escolha explícita
        assertFalse(Gpu.enabled(antigo.withAccel(Settings.Accel.CPU_ONLY), true, false));
        assertFalse(Gpu.enabled(antigo.withAccel(Settings.Accel.AUTO), false, false)); // fora do Mac
    }

    @Test
    void detectaM1() {
        assertTrue(Gpu.isM1("MacBookAir10,1", ""));
        assertTrue(Gpu.isM1("", "Apple M1 Pro"));
        assertFalse(Gpu.isM1("Mac14,2", "Apple M2"));
    }
}
