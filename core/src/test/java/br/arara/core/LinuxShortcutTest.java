package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Sem GNOME de verdade: o gsettings é um mapa em memória. */
class LinuxShortcutTest {
    /** Guarda o que foi gravado: "esquema chave" -> valor (texto do GVariant). */
    static class Fake implements LinuxShortcut.Gs {
        final Map<String, String> values = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean broken;

        Fake(String list) {
            values.put(LinuxShortcut.SCHEMA + " " + LinuxShortcut.LIST, list);
        }

        public String run(String... a) {
            calls.add(String.join(" ", a));
            if (broken) return null;
            switch (a[0]) {
                case "get" -> { return values.get(a[1] + " " + a[2]); }
                case "set" -> { values.put(a[1] + " " + a[2], a[3]); return ""; }
                default -> { return ""; }
            }
        }

        String binding(String path) {
            return values.get(LinuxShortcut.SCHEMA + ".custom-keybinding:" + path + " binding");
        }
    }

    private static Settings hotkey(String h) {
        return Settings.defaults().withHotkey(h);
    }

    @Test
    void traduzOAtalhoParaOGnome() {
        assertEquals("<Control><Shift>space", LinuxShortcut.accelerator("Ctrl+Shift+Space"));
        assertEquals("<Alt>F9", LinuxShortcut.accelerator("Alt+F9"));
        assertEquals("<Super><Shift>d", LinuxShortcut.accelerator("Win+Shift+D"));
        assertEquals("<Control>Page_Up", LinuxShortcut.accelerator("Ctrl+PageUp"));
        assertEquals("F8", LinuxShortcut.accelerator("F8"));
        assertEquals("Pause", LinuxShortcut.accelerator("Pause"));
    }

    @Test
    void mouseEModificadorSozinhoNaoExistemNoGnome() {
        assertNull(LinuxShortcut.accelerator("Mouse4"));
        assertNull(LinuxShortcut.accelerator("RCtrl"));
        assertNull(LinuxShortcut.accelerator("Ctrl"));
        assertNull(LinuxShortcut.accelerator("Ctrl+Shift"));
        assertNull(LinuxShortcut.accelerator("Ctrl+TeclaInventada"));
    }

    @Test
    void soValeNoGnomeComWayland() {
        assertTrue(LinuxShortcut.applies(true, k -> "ubuntu:GNOME"));
        assertTrue(LinuxShortcut.applies(true, k -> "GNOME"));
        assertFalse(LinuxShortcut.applies(false, k -> "GNOME"));
        assertFalse(LinuxShortcut.applies(true, k -> "KDE"));
        assertFalse(LinuxShortcut.applies(true, k -> null));
    }

    @Test
    void leAListaDoGsettings() {
        assertEquals(List.of(), LinuxShortcut.paths("@as []"));
        assertEquals(List.of("/a/", "/b/"), LinuxShortcut.paths("['/a/', '/b/']"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void registraSemApagarOsAtalhosDaPessoa(@TempDir Path dir) throws Exception {
        Fake gs = new Fake("['/outro/custom0/']");
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        assertTrue(s.sync(hotkey("Ctrl+Shift+Space")));
        assertEquals("['/outro/custom0/', '" + LinuxShortcut.TOGGLE + "', '" + LinuxShortcut.ESC + "']",
                gs.values.get(LinuxShortcut.SCHEMA + " " + LinuxShortcut.LIST));
        assertEquals("\"<Control><Shift>space\"", gs.binding(LinuxShortcut.TOGGLE));
        assertEquals("\"\"", gs.binding(LinuxShortcut.ESC)); // o Esc começa solto
        assertTrue(Files.isExecutable(dir.resolve(LinuxShortcut.SCRIPT)));
        String cmd = gs.values.get(LinuxShortcut.SCHEMA + ".custom-keybinding:" + LinuxShortcut.TOGGLE + " command");
        assertTrue(cmd.contains(dir.resolve(LinuxShortcut.SCRIPT).toString()) && cmd.contains("alternar"), cmd);

        // de novo: não duplica na lista
        gs.calls.clear();
        s.sync(hotkey("Alt+F9"));
        assertEquals("\"<Alt>F9\"", gs.binding(LinuxShortcut.TOGGLE));
        assertTrue(gs.calls.stream().noneMatch(c -> c.startsWith("set " + LinuxShortcut.SCHEMA + " " + LinuxShortcut.LIST)));
        s.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void atalhoQueOGnomeNaoTemFicaSemTecla(@TempDir Path dir) {
        Fake gs = new Fake("@as []");
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        assertFalse(s.sync(hotkey("Mouse4")));
        assertEquals("\"\"", gs.binding(LinuxShortcut.TOGGLE));
        s.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void gsettingsQuebradoNaoDerrubaNada(@TempDir Path dir) {
        Fake gs = new Fake("@as []");
        gs.broken = true;
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        assertFalse(s.sync(hotkey("Ctrl+Shift+Space")));
        s.escape(true); // sem registro, não tenta prender o Esc
        s.close();
        assertTrue(gs.calls.stream().noneMatch(c -> c.contains("Escape")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void escSoFicaPresoDuranteODitado(@TempDir Path dir) {
        Fake gs = new Fake("@as []");
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        Settings[] cfg = {hotkey("Ctrl+Shift+Space")};
        SettingsStore store = new SettingsStore() {
            public Settings load() { return cfg[0]; }
            public void save(Settings x) { cfg[0] = x; }
        };
        s.sync(cfg[0]);
        DictationListener l = s.listener(store);
        List<String> seen = new ArrayList<>();
        for (DictationState st : new DictationState[] {DictationState.LISTENING, DictationState.SILENCE,
                DictationState.TRANSCRIBING, DictationState.PASTED, DictationState.IDLE}) {
            l.onState(st);
            seen.add(gs.binding(LinuxShortcut.ESC));
        }
        assertEquals(List.of("\"Escape\"", "\"Escape\"", "\"Escape\"", "\"\"", "\"\""), seen);

        cfg[0] = cfg[0].withEscCancels(false); // "Esc cancela" desligado: nunca prende
        l.onState(DictationState.LISTENING);
        assertEquals("\"\"", gs.binding(LinuxShortcut.ESC));
        s.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void trocarOAtalhoNasConfiguracoesRefazORegistro(@TempDir Path dir) {
        Fake gs = new Fake("@as []");
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        Settings[] cfg = {hotkey("Ctrl+Shift+Space")};
        SettingsStore store = s.wrap(new SettingsStore() {
            public Settings load() { return cfg[0]; }
            public void save(Settings x) { cfg[0] = x; }
        });
        assertEquals("\"<Control><Shift>space\"", gs.binding(LinuxShortcut.TOGGLE));
        store.save(store.load().withHotkey("Alt+F9"));
        assertEquals("\"<Alt>F9\"", gs.binding(LinuxShortcut.TOGGLE));
        s.close();
    }

    /** Com o teclado lido pelo evdev o atalho do GNOME só segura a tecla: o comando não pode alternar de novo. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void comEvdevOAtalhoSoEngoleATecla(@TempDir Path dir) {
        Fake gs = new Fake("@as []");
        LinuxShortcut s = new LinuxShortcut(gs, dir, true, Runnable::run);
        assertTrue(s.sync(hotkey("Ctrl+Shift+Space")));
        assertEquals("\"<Control><Shift>space\"", gs.binding(LinuxShortcut.TOGGLE));
        assertEquals("\"true\"", gs.values.get(LinuxShortcut.SCHEMA + ".custom-keybinding:" + LinuxShortcut.TOGGLE + " command"));
        // o Esc segue no script: é ele que solta a tecla se o Arara morrer no meio do ditado
        assertTrue(gs.values.get(LinuxShortcut.SCHEMA + ".custom-keybinding:" + LinuxShortcut.ESC + " command").contains("cancelar"));
        assertTrue(s.sync(hotkey("RCtrl"))); // o GNOME não registra, mas o evdev ouve
        assertEquals("\"\"", gs.binding(LinuxShortcut.TOGGLE));
        assertFalse(s.sync(hotkey("Mouse4"))); // o evdev só lê teclados
        s.close();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void removerTiraSoOsDoArara(@TempDir Path dir) {
        Fake gs = new Fake("['/outro/custom0/']");
        LinuxShortcut s = new LinuxShortcut(gs, dir, false, Runnable::run);
        s.sync(hotkey("Ctrl+Shift+Space"));
        s.remove();
        assertEquals("['/outro/custom0/']", gs.values.get(LinuxShortcut.SCHEMA + " " + LinuxShortcut.LIST));
        assertFalse(Files.exists(dir.resolve(LinuxShortcut.SCRIPT)));
        s.close();
    }
}
