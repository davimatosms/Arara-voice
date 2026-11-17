package br.arara.app;

import br.arara.core.DictationState;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("Ctrl+Shift+Space", SettingsPage.hotkey(ctrlShift, KeyEvent.VK_SPACE, false));
        assertEquals("Alt+F9", SettingsPage.hotkey(KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_F9, false));
        assertEquals("Ctrl+D", SettingsPage.hotkey(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_D, false));
        assertEquals("Ctrl+D", SettingsPage.hotkey(KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK, KeyEvent.VK_D, false));
        assertNull(SettingsPage.hotkey(ctrlShift, KeyEvent.VK_SHIFT, false));
    }

    @Test
    void gravaAtalhoNoMac() {
        assertEquals("Ctrl+Shift+Space",
                SettingsPage.hotkey(KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SPACE, true));
        assertEquals("Cmd+Option+K", SettingsPage.hotkey(KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_K, true));
        assertNull(SettingsPage.hotkey(KeyEvent.META_DOWN_MASK, KeyEvent.VK_META, true));
        assertEquals("Cmd", Onboarding.keyName(KeyEvent.VK_META, true));
        assertEquals("Option", Onboarding.keyName(KeyEvent.VK_ALT, true));
        assertEquals("Alt", Onboarding.keyName(KeyEvent.VK_ALT, false));
    }

    @Test
    void recusaAtalhoRuim() {
        assertNotNull(SettingsPage.tip("Ctrl+Alt+D", false), "Ctrl+Alt vira AltGr");
        assertNotNull(SettingsPage.tip("D", false), "letra sozinha");
        assertNull(SettingsPage.tip("Ctrl+Alt+Shift+D", false));
        assertNull(SettingsPage.tip("F9", false));
        assertNull(SettingsPage.tip("Ctrl+Shift+Space", false));
    }

    @Test
    void recusaAtalhoRuimNoMac() {
        for (String bad : new String[] {"Cmd+Space", "Ctrl+Space", "Ctrl+Option+Space", "Cmd+Shift+Space", "Option+D", "D"})
            assertNotNull(SettingsPage.tip(bad, true), bad);
        for (String ok : new String[] {"Ctrl+Shift+Space", "Cmd+Option+K", "Ctrl+Option+D", "F9"})
            assertNull(SettingsPage.tip(ok, true), ok);
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

    @Test
    void primeiraExecucaoSoSemConfig(@TempDir Path dir) throws Exception {
        assertTrue(Onboarding.firstRun(dir), "pasta nova: mostra");
        Files.writeString(dir.resolve("config.json"), "{}");
        assertFalse(Onboarding.firstRun(dir), "quem já usa o app não vê de novo");
    }

    @Test
    void ordemEEstadoDosPassos() {
        assertEquals(6, Onboarding.STEPS.length);
        assertEquals("Boas-vindas", Onboarding.STEPS[0]);
        assertEquals("Pronto", Onboarding.STEPS[5]);
        assertEquals(Onboarding.Mark.DONE, Onboarding.mark(0, 2));
        assertEquals(Onboarding.Mark.CURRENT, Onboarding.mark(2, 2));
        assertEquals(Onboarding.Mark.TODO, Onboarding.mark(3, 2));
        assertEquals("Ctrl + Shift + Espaço", Onboarding.spoken("Ctrl+Shift+Space"));
        assertEquals("Ctrl", Onboarding.keyName(KeyEvent.VK_CONTROL));
        assertEquals("Space", Onboarding.keyName(KeyEvent.VK_SPACE));
    }

    @Test
    void velocidadeMediaETempoRestante() {
        ModelDownload.Rate rate = new ModelDownload.Rate();
        long s = 1_000_000_000L;
        assertEquals(0, rate.add(0, 0), "uma amostra só: sem velocidade");
        assertEquals(8e6, rate.add(s, 8_000_000), 1);
        assertEquals(9e6, rate.add(2 * s, 18_000_000), 1, "média desde o começo da janela");
        for (int i = 3; i <= 10; i++) rate.add(i * s, 18_000_000 + (i - 2) * 2_000_000L);
        assertEquals(2e6, rate.add(11 * s, 36_000_000), 1e5, "só os últimos ~5 s contam");
        assertEquals("8,4 MB/s · faltam 26 s", ModelDownload.detail(8.4e6, 356_000_000, 574_000_000));
        assertEquals("1,0 MB/s · faltam 4 min", ModelDownload.detail(1e6, 356_000_000, 574_000_000));
        assertEquals("calculando a velocidade…", ModelDownload.detail(0, 0, 574_000_000));
        assertEquals("356 de 574 MB", ModelDownload.mb(356_000_000, 574_000_000));
    }

    @Test
    void textoAoVivoQuebraPorPalavraEMostraAsDuasUltimasLinhas() {
        java.util.List<String> words = java.util.List.of("aa", "bb", "cc", "dd", "ee", "ff", "gg");
        java.util.List<Integer> starts = LiveText.wrap(words, String::length, 5); // "aa bb" cabe, "aa bb cc" não
        assertEquals(java.util.List.of(0, 2, 4, 6), starts);
        assertEquals(java.util.List.of(0), LiveText.wrap(java.util.List.of("palavralongademais"), String::length, 5));

        LiveText live = new LiveText();
        String longo = "Oi, pessoal! Só passando para avisar que a reunião de amanhã mudou para as dez horas, "
                + "na sala grande do segundo andar. Quem não puder, me avisa por aqui.";
        live.setText(longo, true, 0);
        assertTrue(live.top() >= 2, "texto longo: só as 2 últimas linhas ficam na área");
        assertEquals(longo.split(" ").length, live.shown());
    }

    @Test
    void textoAoVivoRolaNoMaximoUmaVezACada400ms() {
        LiveText live = new LiveText();
        String linha = "WWWWWWWWWWWWWWWW"; // cada palavra ocupa uma linha inteira
        live.setText(String.join(" ", linha, linha, linha, linha, linha), false, 0);
        java.util.List<Long> rolagens = new java.util.ArrayList<>();
        int top = live.top();
        for (long t = 0; t <= 3000; t += 33) {
            live.tick(t);
            if (live.top() != top) {
                assertEquals(top + 1, live.top(), "sobe uma linha por vez");
                top = live.top();
                rolagens.add(t);
            }
        }
        assertEquals(5, live.shown());
        assertEquals(3, live.top());
        assertEquals(3, rolagens.size());
        for (int i = 1; i < rolagens.size(); i++) assertTrue(rolagens.get(i) - rolagens.get(i - 1) >= 400, rolagens.toString());
    }

    @Test
    void previaVemDepoisDoConfirmadoESomeQuandoOTrechoConfirma() {
        LiveText live = new LiveText();
        live.setLive("Oi pessoal", "só passando", 0);
        for (long t = 0; t < 2000; t += 33) live.tick(t);
        assertEquals(4, live.shown(), "confirmado + prévia na tela");
        live.setLive("Oi pessoal só passando", "", 2000);
        assertEquals(4, live.shown(), "a prévia confirmada fica na tela, sem animar de novo");
        live.setLive("Oi pessoal só", "", 2100);
        assertEquals(3, live.shown(), "prévia que o modelo escolhido não confirmou sai");
    }

    @Test
    void seloDizPausaComTextoESilencioSemTexto() {
        RecordingPopup popup = new RecordingPopup(new br.arara.app.demo.DemoCore().settings());
        popup.show(DictationState.LISTENING);
        popup.show(DictationState.SILENCE);
        assertEquals("Silêncio", popup.badge.getText());
        popup.onLiveText("Oi, pessoal!", "");
        assertEquals("Pausa", popup.badge.getText());
        popup.show(DictationState.LISTENING);
        popup.show(DictationState.SILENCE);
        assertEquals("Pausa", popup.badge.getText());
        popup.show(DictationState.IDLE);
        popup.show(DictationState.LISTENING); // ditado novo começa sem texto
        popup.show(DictationState.SILENCE);
        assertEquals("Silêncio", popup.badge.getText());
    }

    @Test
    void singleKeyRules() {
        for (String ok : new String[] {"F1", "F24", "Pause", "RCtrl", "RAlt", "ScrollLock"})
            assertNull(SettingsPage.tip(ok, false), ok);
        for (String bad : new String[] {"A", "5", "Space", "Enter", "Tab", "Esc", "Left", "Ctrl", "Alt", "Shift", "Win", "CapsLock", "Delete", "PageDown", "Home"})
            org.junit.jupiter.api.Assertions.assertNotNull(SettingsPage.tip(bad, false), bad);
        assertNull(SettingsPage.soloWarning("Ctrl+Shift+Space"));
        org.junit.jupiter.api.Assertions.assertNotNull(SettingsPage.soloWarning("F9"));
        assertEquals("RShift", SettingsPage.soloModifier(KeyEvent.VK_SHIFT, KeyEvent.KEY_LOCATION_RIGHT));
        assertEquals("F20", SettingsPage.hotkey(0, KeyEvent.VK_F20, false));
    }
}
