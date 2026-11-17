package br.arara.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MacAppTest {
    @Test
    void acha_o_bundle() {
        assertEquals(Optional.of("/Applications/Arara.app"), MacApp.bundle("/Applications/Arara.app/Contents/MacOS/Arara"));
        assertEquals(Optional.empty(), MacApp.bundle("/usr/bin/java"));
        assertEquals(Optional.empty(), MacApp.bundle(null));
    }

    @Test
    void detecta_pasta_errada() {
        assertTrue(MacApp.wrongPlace("/Volumes/Arara/Arara.app/Contents/MacOS/Arara"));
        assertTrue(MacApp.wrongPlace("/private/var/folders/x/AppTranslocation/ABC/d/Arara.app/Contents/MacOS/Arara"));
        assertFalse(MacApp.wrongPlace("/Applications/Arara.app/Contents/MacOS/Arara"));
        assertFalse(MacApp.wrongPlace(null));
    }
}
