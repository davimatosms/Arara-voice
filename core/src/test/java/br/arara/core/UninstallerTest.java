package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstallerTest {
    @TempDir Path home;
    final List<String> log = new ArrayList<>();

    Uninstaller make(Uninstaller.Os os, Path dataDir, Path exe, Path appImage) {
        return new Uninstaller(os, dataDir, List.of(home), () -> log.add("startup"), c -> log.add("run " + c.get(0)),
                p -> log.add("trash " + p), () -> log.add("release"), exe, appImage);
    }

    Path data() throws IOException {
        Path d = Files.createDirectories(home.resolve("Arara"));
        Files.createDirectories(d.resolve("models"));
        Files.writeString(d.resolve("models/m.bin"), "xx");
        Files.writeString(d.resolve("config.json"), "{}");
        return d;
    }

    @Test
    void desmarcadoNaoApagaDadosMasDesligaStartup() throws IOException {
        Path d = data();
        make(Uninstaller.Os.LINUX, d, null, null).run(false);
        assertTrue(Files.exists(d.resolve("config.json")));
        assertTrue(log.contains("startup"));
    }

    @Test
    void marcadoApagaSoAPastaArara() throws IOException {
        Path d = data();
        Path outro = Files.writeString(home.resolve("outro.txt"), "fica");
        assertEquals(4, make(Uninstaller.Os.LINUX, d, null, null).dataSize().getAsLong());
        var o = make(Uninstaller.Os.LINUX, d, null, null).run(true);
        assertFalse(Files.exists(d));
        assertTrue(Files.exists(outro));
        assertTrue(o.message().contains("AppImage"));
    }

    @Test
    void recusaCaminhoForaDoEsperado() throws IOException {
        Path fora = Files.createDirectories(Files.createTempDirectory("fora").resolve("Arara"));
        Files.writeString(fora.resolve("a"), "x");
        make(Uninstaller.Os.LINUX, fora, null, null).run(true);
        assertTrue(Files.exists(fora.resolve("a")));
        Path outroNome = Files.createDirectories(home.resolve("Documentos"));
        Files.writeString(outroNome.resolve("a"), "x");
        make(Uninstaller.Os.LINUX, outroNome, null, null).run(true);
        assertTrue(Files.exists(outroNome.resolve("a")));
    }

    @Test
    void windowsChamaUnins000AoLadoDoExe() throws IOException {
        Path app = Files.createDirectories(home.resolve("app"));
        Path exe = Files.writeString(app.resolve("Arara.exe"), "");
        Files.writeString(app.resolve("unins000.exe"), "");
        var o = make(Uninstaller.Os.WINDOWS, data(), exe, null).run(false);
        assertEquals(Uninstaller.Step.EXIT, o.step());
        assertTrue(log.contains("run " + app.resolve("unins000.exe")));
    }

    @Test
    void windowsSemUninsDaInstrucao() throws IOException {
        var o = make(Uninstaller.Os.WINDOWS, data(), home.resolve("java.exe"), null).run(false);
        assertEquals(Uninstaller.Step.MESSAGE, o.step());
        assertTrue(o.message().contains("Configurações > Aplicativos"));
    }

    @Test
    void macMoveOBundleParaALixeira() throws IOException {
        Path exe = home.resolve("Arara.app/Contents/MacOS/Arara");
        var o = new Uninstaller(Uninstaller.Os.MAC, data(), List.of(home), () -> {}, c -> {}, p -> {
            log.add("trash " + p);
            return true;
        }, () -> {}, exe, null).run(false);
        assertEquals(Uninstaller.Step.FAREWELL, o.step());
        assertTrue(log.contains("trash " + home.resolve("Arara.app")));
        assertNull(o.message());
    }
}
