package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.DictationEngine;
import br.arara.core.DictationState;
import br.arara.core.HistoryEntry;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comandos de voz na interface: a espera do "Aprovado" no pop-up e a dica do "Arara" na Início. */
class VoiceUiTest {
    @Test
    void popupPedeOAprovadoComOTextoNaTelaEAContagemCaindo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            RecordingPopup popup = new RecordingPopup(new DemoCore().settings());
            long[] t = {0};
            popup.clock = () -> t[0];
            popup.show(DictationState.LISTENING);
            popup.onResult(new HistoryEntry(LocalDateTime.now(), "Texto que espera aprovação"));
            popup.show(DictationState.AWAITING_APPROVAL);
            assertEquals("Diga “Aprovado”", popup.badge.getText());
            assertTrue(popup.live.hasText(), "o texto fica na tela para a pessoa conferir antes de aprovar");
            popup.tick();
            int full = popup.countdown();
            t[0] = DictationEngine.APPROVAL_TIMEOUT_MS / 2;
            popup.tick();
            int half = popup.countdown();
            assertTrue(full > 900, "começa cheia");
            assertTrue(half > 400 && half < 600, "na metade do prazo, metade da barra: " + half);
        });
    }

    @Test
    void inicioMostraADicaDoAraraSoComOChamadoLigado() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            view.show(0);
            HomePage home = view.homePage();
            assertFalse(home.voiceHint.isVisible());
            core.settings().save(core.settings().load().withVoiceWake(true));
            view.show(0);
            assertTrue(home.voiceHint.isVisible());
        });
    }

    @Test
    void seloDaJanelaMostraAEspera() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            view.onState(DictationState.AWAITING_APPROVAL);
            assertEquals("Aguardando aprovação", view.statusText());
        });
    }
}
