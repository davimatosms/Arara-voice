package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class StartupTest {
    static final String EXE = "C:\\Users\\Fulano de Tal\\AppData\\Local\\Programs\\Arara\\Arara.exe";

    static final class FakeRegistry implements Startup.Registry {
        final List<String> calls = new ArrayList<>();

        public void set(String name, String value) {
            calls.add("set " + name + "=" + value);
        }

        public void delete(String name) {
            calls.add("delete " + name);
        }
    }

    static final class MemoryStore implements SettingsStore {
        Settings s = Settings.defaults();

        public Settings load() {
            return s;
        }

        public void save(Settings settings) {
            s = settings;
        }
    }

    static Settings withStart(Settings x, boolean on) {
        return new Settings(x.hotkey(), x.model(), x.accel(), x.microphone(), on, x.showPopup(), x.escCancels());
    }

    @Test
    void syncsAtStartAndWhenToggled() {
        FakeRegistry reg = new FakeRegistry();
        SettingsStore store = new Startup(reg, "Arara", Optional.of(EXE), false).wrap(new MemoryStore());
        assertEquals(List.of("set Arara=\"" + EXE + "\""), reg.calls); // padrão é ligado: regrava o caminho

        store.save(withStart(store.load(), false));
        store.save(withStart(store.load(), false)); // sem mudança: não chama o reg.exe de novo
        store.save(withStart(store.load(), true));
        assertEquals(List.of("set Arara=\"" + EXE + "\"", "delete Arara", "set Arara=\"" + EXE + "\""), reg.calls);
        assertTrue(store.load().startWithWindows());
    }

    @Test
    void devNeverTouchesRegistry() {
        FakeRegistry reg = new FakeRegistry();
        for (Optional<String> cmd : List.of(Optional.of("C:\\Program Files\\Java\\bin\\java.exe"),
                Optional.of("C:\\x\\javaw.exe"), Optional.<String>empty())) {
            SettingsStore store = new Startup(reg, "Arara", cmd, false).wrap(new MemoryStore());
            store.save(withStart(store.load(), false));
            store.save(withStart(store.load(), true));
        }
        assertEquals(List.of(), reg.calls);
    }

    static final String APP = "/Applications/Arara & Cia.app/Contents/MacOS/Arara";

    @Test
    void macWritesLaunchAgentOnlyInsideTheApp(@TempDir Path home) throws IOException {
        Path agents = home.resolve("Library/LaunchAgents");
        Startup.LaunchAgents la = new Startup.LaunchAgents(agents);
        Path plist = la.file(Startup.LaunchAgents.LABEL);

        // dev: java do JDK, ou o java de dentro do runtime do app; nunca grava
        for (String cmd : List.of("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java",
                "/Applications/Arara.app/Contents/runtime/Contents/Home/bin/java")) {
            SettingsStore store = new Startup(la, Startup.LaunchAgents.LABEL, Optional.of(cmd), true).wrap(new MemoryStore());
            store.save(withStart(store.load(), true));
        }
        assertFalse(Files.exists(plist));

        SettingsStore store = new Startup(la, Startup.LaunchAgents.LABEL, Optional.of(APP), true).wrap(new MemoryStore());
        String xml = Files.readString(plist);
        assertTrue(xml.contains("<string>br.arara.Arara</string>"), xml);
        assertTrue(xml.contains("<string>/Applications/Arara &amp; Cia.app/Contents/MacOS/Arara</string>"), xml);
        assertTrue(xml.contains("<key>RunAtLoad</key><true/>"), xml);

        store.save(withStart(store.load(), false));
        assertFalse(Files.exists(plist));
        store.save(withStart(store.load(), false)); // apagar o que não existe não é erro
    }

    @Test
    void packagedPerSystem() {
        assertTrue(Startup.packaged(EXE, false));
        assertFalse(Startup.packaged(APP, false));
        assertTrue(Startup.packaged(APP, true));
        assertFalse(Startup.packaged(EXE, true));
        assertFalse(Startup.packaged("", true));
        assertFalse(Startup.packaged("", false));
    }

    /** Registro real, só com o valor de teste Arara-teste (nunca o Arara de verdade), apagado no fim. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void realRegistryWithTestValue() throws Exception {
        Startup.RegExe reg = new Startup.RegExe();
        Startup w = new Startup(reg, "Arara-teste", Optional.of(EXE), false);
        try {
            w.sync(true);
            assertEquals("\"" + EXE + "\"", query("Arara-teste"));
            w.sync(false);
            assertEquals(null, query("Arara-teste"));
            w.sync(false); // apagar o que não existe não é erro
        } finally {
            reg.delete("Arara-teste");
        }
    }

    private static String query(String name) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("reg.exe", "query", Startup.RUN_KEY, "/v", name).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
        if (p.waitFor() != 0) return null;
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (t.startsWith(name + " ")) return t.substring(t.indexOf("REG_SZ") + "REG_SZ".length()).strip();
        }
        throw new AssertionError(out);
    }
}
