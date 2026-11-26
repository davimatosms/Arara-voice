package br.arara.core;

import static org.junit.jupiter.api.Assertions.*;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sem /dev/input: os EV_KEY entram por event() e o atalho é um GlobalHotkey de verdade com motor falso. */
class LinuxEvdevTest {
    private static final int CTRL = 29, SHIFT = 42, SPACE = 57, ESC = 1, RCTRL = 97, C = 46;

    /** Motor que só anota as chamadas. */
    static class Engine implements DictationEngine {
        final List<String> calls = new ArrayList<>();
        public void toggle() { calls.add("toggle"); }
        public void press() { calls.add("press"); }
        public void release() { calls.add("release"); }
        public void cancel() { calls.add("cancel"); }
        public DictationState state() { return DictationState.IDLE; }
        public void addListener(DictationListener l) {}
        public void removeListener(DictationListener l) {}
        public void close() {}
    }

    private static LinuxEvdev wire(Engine engine, Settings s) {
        SettingsStore store = new SettingsStore() {
            public Settings load() { return s; }
            public void save(Settings x) {}
        };
        return new LinuxEvdev(GlobalHotkey.unhooked(engine, store));
    }

    private static void tap(LinuxEvdev e, int... codes) {
        for (int c : codes) e.event(c, 1);
        for (int i = codes.length - 1; i >= 0; i--) e.event(codes[i], 0);
    }

    @Test
    void achaSoOsTeclados() {
        String proc = """
                N: Name="Lid Switch"
                H: Handlers=event0\s

                N: Name="AT Translated Set 2 keyboard"
                H: Handlers=sysrq kbd event3 leds\s

                N: Name="USB Optical Mouse"
                H: Handlers=mouse1 event6\s
                """;
        assertEquals(List.of(Path.of("/dev/input/event3")), LinuxEvdev.devices(proc));
    }

    @Test
    void codigosViramOsDoJNativeHook() {
        assertEquals(NativeKeyEvent.VC_ESCAPE, LinuxEvdev.keyCode(1));
        assertEquals(NativeKeyEvent.VC_SPACE, LinuxEvdev.keyCode(57));
        assertEquals(NativeKeyEvent.VC_A, LinuxEvdev.keyCode(30));
        assertEquals(NativeKeyEvent.VC_F9, LinuxEvdev.keyCode(67));
        assertEquals(NativeKeyEvent.VC_F12, LinuxEvdev.keyCode(88));
        assertEquals(NativeKeyEvent.VC_PAGE_UP, LinuxEvdev.keyCode(104));
        assertEquals(NativeKeyEvent.VC_PAUSE, LinuxEvdev.keyCode(119));
        assertEquals(NativeKeyEvent.VC_UNDEFINED, LinuxEvdev.keyCode(71)); // 7 do teclado numérico
        assertEquals(NativeKeyEvent.VC_UNDEFINED, LinuxEvdev.keyCode(240));
    }

    @Test
    void atalhoAlternaUmaVezMesmoComRepeticao() {
        Engine engine = new Engine();
        LinuxEvdev e = wire(engine, Settings.defaults().withHotkey("Ctrl+Shift+Space"));
        e.event(CTRL, 1);
        e.event(SHIFT, 1);
        e.event(SPACE, 1);
        e.event(SPACE, 2); // repetição automática do kernel
        e.event(SPACE, 2);
        e.event(SPACE, 0);
        e.event(SHIFT, 0);
        e.event(CTRL, 0);
        assertEquals(List.of("toggle"), engine.calls);
        tap(e, SPACE); // sem os modificadores não é o atalho
        tap(e, CTRL, SPACE);
        assertEquals(List.of("toggle"), engine.calls);
    }

    @Test
    void segurarParaFalarVeApertarESoltar() {
        Engine engine = new Engine();
        LinuxEvdev e = wire(engine, Settings.defaults().withHotkey("Ctrl+Shift+Space").withHoldToTalk(true));
        e.event(CTRL, 1);
        e.event(SHIFT, 1);
        e.event(SPACE, 1);
        assertEquals(List.of("press"), engine.calls);
        e.event(SPACE, 2);
        e.event(SPACE, 0);
        assertEquals(List.of("press", "release"), engine.calls);
    }

    @Test
    void escCancelaEmQualquerJanela() {
        Engine engine = new Engine();
        LinuxEvdev e = wire(engine, Settings.defaults());
        tap(e, ESC);
        assertEquals(List.of("cancel"), engine.calls);
    }

    @Test
    void modificadorDireitoSozinhoRespeitaOLado() {
        Engine engine = new Engine();
        LinuxEvdev e = wire(engine, Settings.defaults().withHotkey("RCtrl"));
        tap(e, CTRL); // o esquerdo não conta
        assertEquals(List.of(), engine.calls);
        tap(e, RCTRL);
        assertEquals(List.of("toggle"), engine.calls);
        tap(e, RCTRL, C); // Ctrl direito + C é atalho de outra coisa
        assertEquals(List.of("toggle"), engine.calls);
    }
}
