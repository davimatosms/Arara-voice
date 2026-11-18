package br.arara.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

    @Test
    void comando_de_zerar_a_acessibilidade() {
        assertEquals(List.of("/usr/bin/tccutil", "reset", "Accessibility", "br.arara.Arara"), MacApp.resetCommand());
    }

    @Test
    void aviso_de_falha_traz_o_comando_do_terminal() {
        assertTrue(MacApp.resetFailedHelp().contains("tccutil reset Accessibility br.arara.Arara"));
    }

    @Test
    void ajuda_cita_o_tccutil() {
        assertTrue(MacApp.accessHelp().contains("tccutil reset Accessibility br.arara.Arara"));
        assertTrue(MacApp.accessHelp().contains("Zerar e reabrir"));
    }
}
