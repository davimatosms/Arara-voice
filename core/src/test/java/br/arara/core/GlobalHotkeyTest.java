package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlobalHotkeyTest {
    final List<String> calls = new ArrayList<>();
    Settings settings = Settings.defaults().withHoldToTalk(true);

    final DictationEngine engine = new DictationEngine() {
        public void toggle() { calls.add("toggle"); }
        public void press() { calls.add("press"); }
        public void release() { calls.add("release"); }
        public void cancel() { calls.add("cancel"); }
        public DictationState state() { return DictationState.IDLE; }
        public void addListener(DictationListener l) {}
        public void removeListener(DictationListener l) {}
        public void close() {}
    };
    final SettingsStore store = new SettingsStore() {
        public Settings load() { return settings; }
        public void save(Settings s) { settings = s; }
    };
    final GlobalHotkey hk = new GlobalHotkey(engine, store);
    final int mods = NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK;

    NativeKeyEvent key(int id, int code, int m) {
        return new NativeKeyEvent(id, m, 0, code, NativeKeyEvent.CHAR_UNDEFINED);
    }

    @Test
    void holdPressesOnceIgnoringAutoRepeatAndReleasesOnAnyComboKey() {
        for (int i = 0; i < 3; i++) hk.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SPACE, mods));
        hk.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_CONTROL, NativeInputEvent.SHIFT_MASK));
        hk.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SPACE, 0)); // já solto: nada
        assertEquals(List.of("press", "release"), calls);
    }

    @Test
    void unrelatedKeyReleaseIsIgnoredAndToggleModeStillToggles() {
        hk.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SPACE, mods));
        hk.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_A, mods));
        hk.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SPACE, mods));
        settings = settings.withHoldToTalk(false);
        hk.nativeKeyPressed(key(NativeKeyEvent.NATIVE_KEY_PRESSED, NativeKeyEvent.VC_SPACE, mods));
        hk.nativeKeyReleased(key(NativeKeyEvent.NATIVE_KEY_RELEASED, NativeKeyEvent.VC_SPACE, mods));
        assertEquals(List.of("press", "release", "toggle"), calls);
    }
}
