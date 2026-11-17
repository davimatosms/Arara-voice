package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** O que muda entre Windows e macOS, com o sistema passado como parâmetro (roda igual nos dois). */
class PlatformTest {
    @Test
    void dataDirPerSystem() {
        assertEquals(Path.of("C:\\Users\\Ana\\AppData\\Roaming", "Arara"),
                AraraCore.dataDir(false, "C:\\Users\\Ana\\AppData\\Roaming", null, "C:\\Users\\Ana"));
        assertEquals(Path.of("/Users/ana", "Library", "Application Support", "Arara"),
                AraraCore.dataDir(true, null, null, "/Users/ana"));
        assertEquals(Path.of("/x", "Arara"), AraraCore.dataDir(false, null, "/x", "/home/ana"));
        assertEquals(Path.of("/home/ana", ".local", "share", "Arara"), AraraCore.dataDir(false, null, "", "/home/ana"));
    }

    @Test
    void pasteWithCmdOnMac() {
        assertEquals(KeyEvent.VK_META, ClipboardPaster.pasteModifier(true));
        assertEquals(KeyEvent.VK_CONTROL, ClipboardPaster.pasteModifier(false));
    }

    @Test
    void waylandDetection() {
        assertTrue(AraraCore.wayland(true, k -> k.equals("XDG_SESSION_TYPE") ? "wayland" : null));
        assertTrue(AraraCore.wayland(true, k -> k.equals("WAYLAND_DISPLAY") ? "wayland-0" : null));
        assertFalse(AraraCore.wayland(true, k -> k.equals("XDG_SESSION_TYPE") ? "x11" : null));
        assertFalse(AraraCore.wayland(false, k -> "wayland"));
    }
}
