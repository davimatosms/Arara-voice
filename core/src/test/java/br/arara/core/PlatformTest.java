package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** O que muda entre Windows e macOS, com o sistema passado como parâmetro (roda igual nos dois). */
class PlatformTest {
    @Test
    void dataDirPerSystem() {
        assertEquals(Path.of("C:\\Users\\Ana\\AppData\\Roaming", "Arara"),
                AraraCore.dataDir(false, "C:\\Users\\Ana\\AppData\\Roaming", "C:\\Users\\Ana"));
        assertEquals(Path.of("/Users/ana", "Library", "Application Support", "Arara"),
                AraraCore.dataDir(true, null, "/Users/ana"));
    }

    @Test
    void pasteWithCmdOnMac() {
        assertEquals(KeyEvent.VK_META, ClipboardPaster.pasteModifier(true));
        assertEquals(KeyEvent.VK_CONTROL, ClipboardPaster.pasteModifier(false));
    }
}
