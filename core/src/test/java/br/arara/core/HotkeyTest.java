package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import org.junit.jupiter.api.Test;

class HotkeyTest {
    static final int CTRL_SHIFT = NativeInputEvent.CTRL_MASK | NativeInputEvent.SHIFT_MASK;

    @Test
    void parsesKeysAndMouseButtons() {
        assertEquals(new Hotkey(NativeKeyEvent.VC_SPACE, 0, CTRL_SHIFT), Hotkey.parse("Ctrl+Shift+Space"));
        assertEquals(new Hotkey(NativeKeyEvent.VC_A, 0, CTRL_SHIFT), Hotkey.parse(" shift + ctrl + a "));
        assertEquals(new Hotkey(NativeKeyEvent.VC_F9, 0, NativeInputEvent.ALT_MASK), Hotkey.parse("Alt+F9"));
        assertEquals(new Hotkey(NativeKeyEvent.VC_UNDEFINED, 4, 0), Hotkey.parse("Mouse4"));
    }

    @Test
    void macModifiersAreMetaAndAlt() {
        assertEquals(Hotkey.parse("Win+Shift+K"), Hotkey.parse("Cmd+Shift+K"));
        assertEquals(Hotkey.parse("Ctrl+Alt+Shift+K"), Hotkey.parse("Ctrl+Option+Shift+K"));
        assertTrue(Hotkey.parse("Cmd+Shift+K").modifiersMatch(NativeInputEvent.META_L_MASK | NativeInputEvent.SHIFT_L_MASK));
    }

    @Test
    void rejectsInvalid() {
        for (String bad : new String[] {"Ctrl+Shift", "Ctrl+Alt", "Ctrl+Banana", "Mouse1", "A+B", ""}) {
            assertThrows(IllegalArgumentException.class, () -> Hotkey.parse(bad), bad);
        }
    }

    @Test
    void modifiersMustMatchExactlyEitherSide() {
        Hotkey h = Hotkey.parse("Ctrl+Shift+Space");
        assertTrue(h.modifiersMatch(NativeInputEvent.CTRL_L_MASK | NativeInputEvent.SHIFT_R_MASK));
        assertFalse(h.modifiersMatch(NativeInputEvent.CTRL_L_MASK));
        assertFalse(h.modifiersMatch(CTRL_SHIFT | NativeInputEvent.ALT_L_MASK));
    }

    @Test
    void parsesSingleKeysAndRightModifiers() {
        assertEquals(NativeKeyEvent.VC_PAGE_DOWN, Hotkey.parse("PageDown").keyCode());
        assertEquals(NativeKeyEvent.VC_SCROLL_LOCK, Hotkey.parse("ScrollLock").keyCode());
        assertEquals(NativeKeyEvent.VC_F20, Hotkey.parse("F20").keyCode());
        assertTrue(Hotkey.parse("RCtrl").modifierOnly());
        assertEquals(NativeKeyEvent.VC_ALT, Hotkey.parse("RAlt").keyCode());
    }

    @Test
    void anyKeyAloneParses() {
        assertEquals(NativeKeyEvent.VC_A, Hotkey.parse("A").keyCode());
        assertEquals(NativeKeyEvent.VC_NUM_LOCK, Hotkey.parse("Num Lock").keyCode());
        assertTrue(Hotkey.parse("Ctrl").modifierOnly());
        assertEquals(Hotkey.ANY_SIDE, Hotkey.parse("Shift").location());
        assertEquals(NativeKeyEvent.VC_META, Hotkey.parse("Win").keyCode());
    }
}
