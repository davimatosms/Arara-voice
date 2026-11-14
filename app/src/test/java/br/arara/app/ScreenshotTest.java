package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.DictationState;
import br.arara.core.HistoryEntry;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Monta as telas sem mostrar janela e salva PNGs em build/screenshots (serve também de teste de fumaça). */
class ScreenshotTest {
    private static final File DIR = new File("build/screenshots");

    @Test
    void desenhaTelasEPopup() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        DIR.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            view.onState(DictationState.PASTED);
            String[] names = {"1-inicio", "2-historico", "3-dicionario", "4-configuracoes"};
            for (int i = 0; i < names.length; i++) {
                view.show(i);
                save(view, 1100, i == 3 ? 1280 : 720, names[i]);
            }
            RecordingPopup popup = new RecordingPopup(core.settings());
            popup.onResult(new HistoryEntry(LocalDateTime.now(), "Oi, pessoal! Só passando para avisar que a reunião"));
            for (DictationState s : new DictationState[] {
                    DictationState.LISTENING, DictationState.SILENCE, DictationState.TRANSCRIBING, DictationState.PASTED}) {
                popup.show(s);
                save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "popup-" + s.name().toLowerCase());
            }
        });
    }

    /** Quadros da arara viva no pop-up: bico fechado, aberto com notinhas, piscando e o voo do "Colado!". */
    @Test
    void desenhaAraraViva() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        DIR.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            RecordingPopup popup = new RecordingPopup(new DemoCore().settings());
            popup.onResult(new HistoryEntry(LocalDateTime.now(), "Oi, pessoal! Só passando para avisar"));
            popup.show(DictationState.LISTENING);
            popup.onLevel(0.1f);
            popup.tick();
            save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "viva-1-bico-fechado");
            for (int i = 0; i < 20; i++) {
                popup.onLevel(0.6f + 0.3f * (i % 2));
                popup.tick();
                if (i == 3) save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "viva-2-entreaberto");
            }
            while (popup.arara.notes.size() < 3 || popup.arara.sprite == Arara.PISCAR) popup.tick();
            save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "viva-3-aberto-notinhas");
            popup.show(DictationState.SILENCE);
            while (popup.arara.sprite != Arara.PISCAR) popup.tick();
            save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "viva-4-piscando");
            popup.show(DictationState.PASTED);
            for (int t = 1; t <= Arara.VOO.length * Arara.FLY_TICKS; t++) {
                popup.tick();
                if (t % Arara.FLY_TICKS == 1) {
                    save(popup.view, popup.view.getWidth(), popup.view.getHeight(), "viva-5-voo-" + t / Arara.FLY_TICKS);
                }
            }
        });
    }

    /** Põe o componente num JFrame que nunca aparece, só para ter layout de verdade, e desenha num PNG. */
    private static void save(JComponent c, int w, int h, String name) {
        JFrame f = new JFrame();
        f.setUndecorated(true);
        f.setContentPane(c);
        f.addNotify();
        f.setSize(w, h);
        f.validate();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        f.setContentPane(new JPanel());
        f.dispose();
        try {
            ImageIO.write(img, "png", new File(DIR, name + ".png"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
