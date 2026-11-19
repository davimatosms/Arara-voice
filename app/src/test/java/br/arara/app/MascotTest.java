package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.DictationState;
import br.arara.core.Settings;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Estilo "Só a arara": a arara sem fundo, no canto da tela, voando enquanto transcreve. */
class MascotTest {
    private static boolean flying(Arara a) {
        return Arrays.asList(Arara.VOO).contains(a.sprite);
    }

    @Test
    void pousadaEnquantoOuveESemLegenda() {
        Mascot m = new Mascot();
        m.show(DictationState.LISTENING, null);
        for (int t = 1; t <= 60; t++) m.animate(DictationState.LISTENING, 0.3f, t);
        assertFalse(flying(m.arara), "ouvindo: pousada, com o bico seguindo a voz");
        assertEquals(0, m.arara.dx);
        assertFalse(m.caption.isVisible());
    }

    @Test
    void voaDeUmLadoParaOOutroEnquantoTranscreve() {
        Mascot m = new Mascot();
        m.show(DictationState.TRANSCRIBING, null);
        Set<Integer> xs = new HashSet<>();
        int min = 0, max = 0;
        for (int t = 1; t <= Mascot.FLIGHT_TICKS; t++) {
            m.animate(DictationState.TRANSCRIBING, 0, t);
            assertTrue(flying(m.arara), "transcrevendo: quadro de voo no tick " + t);
            xs.add(m.arara.dx);
            min = Math.min(min, m.arara.dx);
            max = Math.max(max, m.arara.dx);
        }
        assertTrue(xs.size() > 10, "a arara se desloca, não voa parada");
        assertTrue(min < 0 && max > 0, "vai para os dois lados");
        assertTrue(max <= Mascot.DRIFT && -min <= Mascot.DRIFT, "sem sair da janela");
    }

    @Test
    void legendaSoComErroOuTempoRestante() {
        Mascot m = new Mascot();
        m.show(DictationState.ERROR, "Não consegui colar. O texto está no histórico.");
        assertTrue(m.caption.isVisible());
        assertEquals("Não consegui colar. O texto está no histórico.", m.captionText());
        m.show(DictationState.TRANSCRIBING, "Faltam ~5 s");
        assertTrue(m.caption.isVisible());
        m.show(DictationState.LISTENING, null);
        assertFalse(m.caption.isVisible());
    }

    @Test
    void popupUsaOMascoteQuandoConfigurado() {
        DemoCore core = new DemoCore();
        RecordingPopup popup = new RecordingPopup(core.settings());
        assertSame(popup.view, popup.content(), "padrão: pop-up completo, como sempre");

        core.settings().save(core.settings().load().withPopupStyle(Settings.PopupStyle.ARARA));
        assertSame(popup.mascot, popup.content());
        assertTrue(popup.mascot.getPreferredSize().width * popup.mascot.getPreferredSize().height
                < RecordingPopup.WIDTH * RecordingPopup.HEIGHT / 2, "bem menor que o pop-up completo");

        popup.show(DictationState.TRANSCRIBING);
        for (int i = 0; i < 10; i++) popup.tick();
        assertTrue(flying(popup.mascot.arara), "o pop-up anima o mascote quando ele está em uso");
    }
}
