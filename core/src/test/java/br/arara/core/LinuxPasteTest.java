package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LinuxPasteTest {
    static class Fake implements LinuxPaste.Env {
        final Set<String> tools;
        final boolean socket;
        final Set<String> failing;
        String token = "";

        Fake(Set<String> tools, boolean socket, Set<String> failing) {
            this.tools = tools;
            this.socket = socket;
            this.failing = failing;
        }

        public boolean has(String t) { return tools.contains(t); }
        public boolean ydotoolSocket() { return socket; }
        public LinuxPaste.Out run(int ms, String stdin, String... cmd) {
            if (failing.contains(cmd[0])) return new LinuxPaste.Out(2, "");
            return new LinuxPaste.Out(0, "TOKEN=novo\n");
        }
        public String token() { return token; }
        public void saveToken(String t) { token = t; }
    }

    @Test
    void escolhePelaOrdem() {
        assertEquals("wtype", LinuxPaste.press(new Fake(Set.of("wtype", "ydotool", "python3"), true, Set.of())));
        assertEquals("ydotool", LinuxPaste.press(new Fake(Set.of("wtype", "ydotool"), true, Set.of("wtype"))));
        assertEquals("portal", LinuxPaste.press(new Fake(Set.of("ydotool", "python3"), false, Set.of())));
    }

    /** Terminais: o Shift entra em todas as estratégias. */
    @Test
    void ctrlShiftVMandaOShift() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (String tool : new String[] {"wtype", "ydotool", "python3"}) {
            Fake f = new Fake(Set.of(tool), true, Set.of()) {
                @Override public LinuxPaste.Out run(int ms, String stdin, String... cmd) {
                    seen.add(String.join(" ", cmd).replaceAll("(?s)import sys.*?(?= shift$|$)", "<script>"));
                    return super.run(ms, stdin, cmd);
                }
            };
            assertNotNull(LinuxPaste.press(f, true));
        }
        assertEquals("wtype -M ctrl -M shift v -m shift -m ctrl", seen.get(0));
        assertEquals("ydotool key 29:1 42:1 47:1 47:0 42:0 29:0", seen.get(1));
        assertTrue(seen.get(2).endsWith(" shift"), seen.get(2));
    }

    @Test
    void portalGuardaToken() {
        Fake f = new Fake(Set.of("python3"), false, Set.of());
        assertEquals("portal", LinuxPaste.press(f));
        assertEquals("novo", f.token);
    }

    @Test
    void tudoFalhaDevolveNull() {
        assertNull(LinuxPaste.press(new Fake(Set.of("wtype", "ydotool", "python3"), true, Set.of("wtype", "ydotool", "python3"))));
        assertNull(LinuxPaste.press(new Fake(Set.of(), false, Set.of())));
    }

    @Test
    void tokenRecusadoTentaDeNovoSemToken() {
        Fake f = new Fake(Set.of("python3"), false, Set.of()) {
            int calls;

            @Override
            public LinuxPaste.Out run(int ms, String stdin, String... cmd) {
                return calls++ == 0 ? new LinuxPaste.Out(2, "") : super.run(ms, stdin, cmd);
            }
        };
        f.token = "velho";
        assertEquals("portal", LinuxPaste.press(f));
        assertEquals("novo", f.token);
    }
}
