package br.arara.app;

import br.arara.core.DictationState;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicTest {
    @Test
    void tempoRelativoDoHistorico() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 14, 32);
        assertEquals("Agora há pouco", HistoryPage.when(now.minusSeconds(30), now));
        assertEquals("Há 12 min", HistoryPage.when(now.minusMinutes(12), now));
        assertEquals("Hoje, 09:15", HistoryPage.when(now.withHour(9).withMinute(15), now));
        assertEquals("Ontem, 18:02", HistoryPage.when(LocalDateTime.of(2026, 11, 12, 18, 2), now));
        assertEquals("01/11/2026, 08:00", HistoryPage.when(LocalDateTime.of(2026, 11, 1, 8, 0), now));
    }

    @Test
    void gravaAtalho() {
        int ctrlShift = KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK;
        assertEquals("Ctrl+Shift+Space", SettingsPage.hotkey(ctrlShift, KeyEvent.VK_SPACE));
        assertEquals("Alt+F9", SettingsPage.hotkey(KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_F9));
        assertEquals("Ctrl+D", SettingsPage.hotkey(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_D));
        assertNull(SettingsPage.hotkey(ctrlShift, KeyEvent.VK_SHIFT));
    }

    @Test
    void recusaAtalhoRuim() {
        assertNotNull(SettingsPage.problem("Ctrl+Alt+D"), "Ctrl+Alt vira AltGr");
        assertNotNull(SettingsPage.problem("D"), "letra sozinha");
        assertNull(SettingsPage.problem("Ctrl+Alt+Shift+D"));
        assertNull(SettingsPage.problem("F9"));
        assertNull(SettingsPage.problem("Ctrl+Shift+Space"));
    }

    @Test
    void bicoTemHisterese() {
        assertEquals(0, Arara.beakFor(0, 0.40f), "ruído de fundo não abre");
        assertEquals(1, Arara.beakFor(0, 0.50f));
        assertEquals(1, Arara.beakFor(1, 0.40f), "entre os limiares fica como está");
        assertEquals(2, Arara.beakFor(1, 0.70f));
        assertEquals(2, Arara.beakFor(2, 0.58f), "só fecha bem abaixo de onde abriu");
        assertEquals(1, Arara.beakFor(2, 0.45f));
        assertEquals(0, Arara.beakFor(1, 0.30f));
    }

    @Test
    void notinhasSobemESomem() {
        Arara a = new Arara(2);
        for (int t = 1; t <= 60; t++) {
            a.animate(DictationState.LISTENING, 1f, t);
            assertTrue(a.notes.size() <= 4, "no máximo 4 no ar");
        }
        assertEquals(2, a.beak);
        assertTrue(a.notes.size() >= 2, "voz alta solta notinhas");
        for (int t = 61; t <= 61 + Arara.NOTE_LIFE; t++) a.animate(DictationState.SILENCE, 0f, t);
        assertTrue(a.notes.isEmpty(), "somem em ~0,6 s");
        assertEquals(0, a.beak);
    }

    @Test
    void voaNoColadoEVoltaAPousar() {
        Arara a = new Arara(2);
        int fly = Arara.VOO.length * Arara.FLY_TICKS * Arara.FLY_CYCLES;
        a.animate(DictationState.PASTED, 0f, 0);
        assertSame(Arara.VOO[0], a.sprite);
        a.animate(DictationState.PASTED, 0f, fly - 1);
        assertSame(Arara.VOO[Arara.VOO.length - 1], a.sprite);
        a.animate(DictationState.PASTED, 0f, fly);
        assertTrue(a.sprite == Arara.IMAGE || a.sprite == Arara.PISCAR, "pousou");
    }
}
