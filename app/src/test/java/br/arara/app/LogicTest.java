package br.arara.app;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
