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

    private static final String FRASE = "Oi, pessoal! Só passando para avisar que a reunião de amanhã mudou para as dez "
            + "horas, na sala grande do segundo andar.";

    private static String ate(int n) {
        return String.join(" ", java.util.Arrays.copyOf(FRASE.split(" "), n));
    }

    /** Pop-up com texto ao vivo (design 12, variação A): estados A1-A5 e quadros da rolagem, para comparar com o FigCraft. */
    @Test
    void desenhaTextoAoVivo() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        String dir = "popup-texto-ao-vivo/";
        new File(DIR, dir).mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            long[] t = {0};
            RecordingPopup p = new RecordingPopup(new DemoCore().settings());
            p.clock = () -> t[0];
            int w = p.view.getWidth(), h = p.view.getHeight();

            p.show(DictationState.LISTENING);
            p.onLevel(0.1f);
            caretOn(p, t);
            save(p.view, w, h, dir + "A1-comecando");

            p.onPartialText(ate(12));
            run(p, t, 3000, 0.8f);
            while (p.arara.notes.size() < 3 || p.arara.sprite == Arara.PISCAR) run(p, t, 33, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "A2-falando");

            p.onPartialText(ate(16));
            run(p, t, 3000, 0.8f);
            p.show(DictationState.SILENCE);
            run(p, t, 300, 0.02f);
            save(p.view, w, h, dir + "A3-pausa");

            p.onPartialText(FRASE);
            run(p, t, 3000, 0.02f);
            p.show(DictationState.TRANSCRIBING);
            run(p, t, 43 * 33, 0);
            save(p.view, w, h, dir + "A4-transcrevendo");

            p.onResult(new HistoryEntry(LocalDateTime.now(), FRASE));
            p.show(DictationState.PASTED);
            while (p.arara.sprite != Arara.VOO[3]) run(p, t, 33, 0);
            save(p.view, w, h, dir + "A5-colado");

            // Rolagem: 2 linhas cheias, chega "mudou para as dez" e a 3ª linha empurra o texto para cima.
            p.show(DictationState.IDLE);
            p.show(DictationState.LISTENING);
            p.onPartialText(ate(11));
            run(p, t, 3000, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "rolagem-1-antes");
            p.onPartialText(ate(15));
            int top = p.live.top();
            while (p.live.top() == top) run(p, t, 33, 0.8f);
            run(p, t, 33, 0.8f);
            save(p.view, w, h, dir + "rolagem-2-subindo");
            run(p, t, 66, 0.8f);
            save(p.view, w, h, dir + "rolagem-3-quase");
            run(p, t, 3000, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "rolagem-4-assentou");

            p.show(DictationState.IDLE);
            p.show(DictationState.LISTENING);
            p.onPartialText(ate(3));
            run(p, t, 3000, 0.8f);
            p.onPartialText(ate(5));
            run(p, t, 99, 0.8f);
            save(p.view, w, h, dir + "palavra-entrando");
        });
    }

    /** Avança o relógio do pop-up em quadros de 33 ms, com o microfone no nível dado. */
    private static void run(RecordingPopup p, long[] t, long ms, float level) {
        for (long end = t[0] + ms; t[0] < end; ) {
            t[0] += 33;
            p.onLevel(level * (0.35f + 0.65f * Math.abs((float) Math.sin(t[0] * 0.07))));
            p.tick();
        }
    }

    /** Leva o relógio para a metade em que o cursor pisca aceso. */
    private static void caretOn(RecordingPopup p, long[] t) {
        t[0] = (t[0] / 1000 + 1) * 1000 + 100;
        p.tick();
    }

    /** Os 6 passos da primeira execução e os 2 erros, nos estados do design (para comparar com o FigCraft). */
    @Test
    void desenhaPrimeiraExecucao() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        DIR.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            ModelDownload download = new ModelDownload(p -> {});
            Onboarding o = new Onboarding(core.engine(), core.settings(), download, (name, chunks) -> () -> {}, () -> true, () -> {});
            String dir = "primeira-execucao/";
            new File(DIR, dir).mkdirs();
            downloading(download, 356_000_000L);
            o.tick = 3;
            o.show(0);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "1-boas-vindas");
            o.show(1);
            o.tick();
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "2-download");
            o.show(2);
            short[] voice = new short[512];
            for (int i = 0; i < 60; i++) {
                for (int j = 0; j < voice.length; j++) voice[j] = (short) ((300 + 26000 * Math.pow(Math.abs(Math.sin(i * 1.7)), 3)) * Math.sin(j / 3.0));
                o.chunk(voice);
                o.tick();
            }
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "3-microfone");
            o.show(3);
            o.held.addAll(java.util.List.of("Ctrl", "Shift"));
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "4-atalho");
            o.show(4);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "5b-primeiro-ditado-esperando-modelo");
            download.phase = ModelDownload.Phase.READY;
            o.show(4);
            o.practice.setText("Oi, Arara! Este é o meu primeiro ditado e o texto apareceu sozinho aqui, com acento e pontuação.");
            o.showPasted(0.4);
            o.practice.setForeground(Theme.TEXT);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "5-primeiro-ditado");
            o.show(5);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "6-pronto");
            downloading(download, 178_000_000L);
            download.phase = ModelDownload.Phase.PAUSED;
            o.show(1);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "erro-sem-internet");
            downloading(download, 356_000_000L);
            o.show(2);
            for (int i = 0; i < 70; i++) o.chunk(new short[512]);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "erro-microfone-bloqueado");
        });
    }

    private static void downloading(ModelDownload d, long done) {
        d.phase = ModelDownload.Phase.DOWNLOADING;
        d.done = done;
        d.total = 574_000_000L;
        d.speed = 8_400_000;
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
