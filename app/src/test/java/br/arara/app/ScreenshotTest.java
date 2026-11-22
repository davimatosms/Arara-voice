package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.DictationState;
import br.arara.core.HistoryEntry;
import br.arara.core.Settings;
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
                save(view, 1100, i == 3 ? 1860 : 720, names[i]);
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

    /** Estilo "Só a arara": ouvindo, silêncio, voo da transcrição (3 quadros) e erro, com fundo transparente. */
    @Test
    void desenhaMascote() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        new File(DIR, "mascote").mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            Mascot m = new Mascot();
            m.show(DictationState.LISTENING, null);
            for (int t = 1; t <= 12; t++) m.animate(DictationState.LISTENING, 0.8f, t);
            save(m, Mascot.WIDTH, Mascot.HEIGHT, "mascote/1-ouvindo");
            m.show(DictationState.SILENCE, null);
            m.animate(DictationState.SILENCE, 0, 1);
            save(m, Mascot.WIDTH, Mascot.HEIGHT, "mascote/2-silencio");
            m.show(DictationState.TRANSCRIBING, "Faltam ~4 s");
            for (int t = 1; t <= Mascot.FLIGHT_TICKS; t++) {
                m.animate(DictationState.TRANSCRIBING, 0, t);
                if (t % (Mascot.FLIGHT_TICKS / 4) == 0) {
                    save(m, Mascot.WIDTH, Mascot.HEIGHT, "mascote/3-transcrevendo-" + t / (Mascot.FLIGHT_TICKS / 4));
                }
            }
            m.show(DictationState.ERROR, "Não detectei fala.");
            m.animate(DictationState.ERROR, 0, 1);
            save(m, Mascot.WIDTH, Mascot.HEIGHT, "mascote/4-erro");
        });
    }

    private static final String FRASE = "Oi, pessoal! Só passando para avisar que a reunião de amanhã mudou para as dez "
            + "horas, na sala grande do segundo andar.";

    private static String entre(int from, int to) {
        return String.join(" ", java.util.Arrays.copyOfRange(FRASE.split(" "), from, to));
    }

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

            p.onLiveText(ate(12), "");
            run(p, t, 3000, 0.8f);
            while (p.arara.notes.size() < 3 || p.arara.sprite == Arara.PISCAR) run(p, t, 33, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "A2-falando");

            p.onLiveText(ate(16), "");
            run(p, t, 3000, 0.8f);
            p.show(DictationState.SILENCE);
            run(p, t, 300, 0.02f);
            save(p.view, w, h, dir + "A3-pausa");

            p.onLiveText(FRASE, "");
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
            p.onLiveText(ate(11), "");
            run(p, t, 3000, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "rolagem-1-antes");
            p.onLiveText(ate(15), "");
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
            p.onLiveText(ate(3), "");
            run(p, t, 3000, 0.8f);
            p.onLiveText(ate(5), "");
            run(p, t, 99, 0.8f);
            save(p.view, w, h, dir + "palavra-entrando");

            // Prévia do modelo leve: confirmado em branco, provisório em #9AAE9E; ao confirmar, vira branco em 150 ms.
            p.show(DictationState.IDLE);
            p.show(DictationState.LISTENING);
            p.onLiveText(ate(6), entre(6, 11));
            run(p, t, 3000, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "previa-1-provisorio");
            p.onLiveText(ate(11), "");
            run(p, t, 66, 0.8f);
            save(p.view, w, h, dir + "previa-2-confirmando");
            p.onLiveText(ate(11), entre(11, 14));
            run(p, t, 3000, 0.8f);
            caretOn(p, t);
            save(p.view, w, h, dir + "previa-3-confirmado-e-nova-previa");
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

    /** Passos do Linux Wayland/GNOME (design 19). */
    @Test
    void desenhaAtalhoUmaTecla() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        DIR.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            ModelDownload download = new ModelDownload(p -> {});
            Onboarding o = new Onboarding(core.engine(), core.settings(), download, (name, chunks) -> () -> {}, () -> true, () -> {});
            new File(DIR, "atalho-uma-tecla/").mkdirs();
            core.settings().save(core.settings().load().withHotkey("PageDown"));
            o.show(3);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, "atalho-uma-tecla/atalho-pagedown");
        });
    }

    @Test
    void desenhaLinux() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        DIR.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            ModelDownload download = new ModelDownload(p -> {});
            download.phase = ModelDownload.Phase.READY;
            Onboarding o = new Onboarding(core.engine(), core.settings(), download, (name, chunks) -> () -> {}, () -> true, () -> {},
                    new Onboarding.Linux(true, true, false));
            String dir = "linux/";
            new File(DIR, dir).mkdirs();
            o.show(6);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "1-atalho");
            o.show(7);
            o.pasteState(1);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "2-colar-aguardando");
            o.pasteState(2);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "3-colar-pronto");
            o.pasteState(3);
            save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "4-colar-negado");
            JPanel card = SettingsPage.linuxCard(new Onboarding.Linux(true, true, false));
            card.setBackground(Theme.BG);
            card.setOpaque(true);
            save(card, 800, 240, dir + "5-configuracoes");
        });
    }

    /** Diálogo de atualização (design 13): 1a, 1d, 2, 3, 4a-4c, Configurações (5) e os estados do "Procurar agora" (5b). */
    /** Desinstalar (design 22): seção das Configurações, diálogo no Mac e no Windows e a despedida. */
    @Test
    void desenhaDesinstalar() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        String dir = "desinstalar/";
        new File(DIR, dir).mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            for (boolean light : new boolean[] {false, true}) {
                Theme.use(Settings.Macaw.CANINDE, light);
                Theme.install();
                String t = light ? "-claro" : "";
                DemoCore core = new DemoCore();
                MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
                view.show(3);
                save(view, 1100, 2900, dir + "1-configuracoes" + t);
                if (light) break;
                UninstallDialog mac = new UninstallDialog(br.arara.core.Uninstaller.Os.MAC, "~/Library/Application Support/Arara");
                mac.size(java.util.OptionalLong.of(1_400_000_000L));
                save(mac, UninstallDialog.WIDTH, mac.getPreferredSize().height, dir + "2a-dialogo-mac");
                UninstallDialog win = new UninstallDialog(br.arara.core.Uninstaller.Os.WINDOWS, "%APPDATA%\\Arara");
                win.size(java.util.OptionalLong.of(1_400_000_000L));
                save(win, UninstallDialog.WIDTH, win.getPreferredSize().height, dir + "2b-dialogo-windows");
                JPanel f = UninstallDialog.farewell(true, null, () -> {});
                save(f, UninstallDialog.WIDTH, f.getPreferredSize().height, dir + "3-despedida-mac");
            }
            Theme.use(Settings.Macaw.CANINDE, false);
        });
    }

    @Test
    void desenhaAtualizacao() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        String dir = "atualizacao/";
        new File(DIR, dir).mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            UpdateDialog.Actions none = new UpdateDialog.Actions() {
                public void install() {}
                public void later() {}
                public void skip() {}
                public void cancel() {}
                public void site() {}
            };
            UpdateDialog d = new UpdateDialog(Main.DEMO_UPDATE, "0.2.0", none);
            BufferedImage offer = dialog(d);
            write(offer, dir + "1d-aviso");
            d.show(UpdateDialog.State.DOWNLOADING);
            d.progress(41_280_000L, 86_000_000L, 3_200_000);
            write(dialog(d), dir + "2-baixando");
            d.show(UpdateDialog.State.INSTALLING);
            write(dialog(d), dir + "3-instalando");
            d.show(UpdateDialog.State.OFFLINE);
            write(dialog(d), dir + "4a-erro-sem-internet");
            d.show(UpdateDialog.State.CORRUPTED);
            write(dialog(d), dir + "4b-erro-arquivo-nao-confere");
            d.corruptedTwice = true;
            d.show(UpdateDialog.State.CORRUPTED);
            write(dialog(d), dir + "4b2-erro-segunda-vez-so-site");
            d.show(UpdateDialog.State.PERMISSION);
            write(dialog(d), dir + "4c-erro-sem-permissao-mac");

            // 1a: a tela Início com o véu do modal e o aviso por cima, como a janela aberta e em foco.
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            BufferedImage main = render(view, 1100, 720);
            Graphics2D g = main.createGraphics();
            g.setColor(new java.awt.Color(0, 0, 0, 140));
            g.fillRect(0, 0, 1100, 720);
            g.drawImage(offer, 290, 128, null);
            g.dispose();
            write(main, dir + "1a-aviso-sobre-a-janela");

            UpdateUi ui = new UpdateUi(new JFrame(), Main.demoUpdates(), null, () -> {});
            SettingsPage settings = view.settingsPage();
            settings.updates(ui);
            view.show(3);
            save(view, 1100, 1860, dir + "5-configuracoes");
            // 5b: só o cartão, um por estado, empilhados.
            String[][] states = {{"Procurar agora", "g"}, {"Procurando…", "s"}, {"Você tem a versão mais nova", "c"},
                    {"Nova versão 0.3.0 · Ver", "y"}, {"Sem internet · Tentar de novo", "e"}};
            JPanel card = settings.updatesCard;
            card.getParent().remove(card);
            int w = 788, h = card.getPreferredSize().height - 20; // sem a margem de baixo do cartão
            BufferedImage all = new BufferedImage(w, h * states.length, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ga = all.createGraphics();
            for (int i = 0; i < states.length; i++) {
                java.awt.Color c = switch (states[i][1]) {
                    case "s" -> Theme.TEXT_SOFT;
                    case "y" -> Theme.SUPPORT;
                    case "e" -> Theme.ERROR;
                    default -> Theme.ACTIVE;
                };
                settings.searchState(states[i][0], c, states[i][1].equals("c"));
                ga.drawImage(render(card, w, h), 0, i * h, null);
            }
            ga.dispose();
            write(all, dir + "5b-estados-do-procurar-agora");
        });
    }

    /**
     * Temas das araras (design 14): Início e pop-up "Ouvindo" com texto ao vivo nos 10 temas, trocando o tema com as
     * telas já abertas (o caminho de verdade); mais Configurações › Aparência e o passo 6 da primeira execução.
     */
    @Test
    void desenhaTemas() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        String dir = "temas/";
        new File(DIR, dir).mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            view.onState(DictationState.PASTED);
            JFrame main = offscreen(view, 1100, 720);
            long[] t = {0};
            RecordingPopup p = new RecordingPopup(core.settings());
            p.clock = () -> t[0];
            JFrame pop = offscreen(p.view, p.view.getWidth(), p.view.getHeight());
            p.show(DictationState.LISTENING);
            p.onLiveText("avisar que a reunião de", "amanhã mudou");
            run(p, t, 3000, 0.8f);
            while (p.arara.notes.size() < 2 || p.arara.sprite != Arara.FALA[2]) run(p, t, 33, 0.8f);
            caretOn(p, t);
            try {
                for (Settings.Macaw m : Settings.Macaw.values()) {
                    for (boolean light : new boolean[] {false, true}) {
                        Theme.apply(m, light);
                        String id = Theme.id(m) + (light ? "-claro" : "");
                        write(paint(main, view), dir + "inicio-" + id);
                        p.tick();
                        write(paint(pop, p.view), dir + "popup-ouvindo-" + id);
                    }
                }
                Theme.apply(Settings.Macaw.CANINDE, false);
                configuracoes(core, dir + "configuracoes-caninde");
                core.settings().save(core.settings().load().withMacaw(Settings.Macaw.AZUL).withLightMode(true).withMacawIcon(true));
                Theme.apply(Settings.Macaw.AZUL, true);
                configuracoes(core, dir + "configuracoes-azul-claro-icone-ligado");
                Theme.apply(Settings.Macaw.CANINDE, false);
                core.settings().save(core.settings().load().withMacaw(Settings.Macaw.CANINDE).withLightMode(false).withMacawIcon(false));
                ModelDownload download = new ModelDownload(x -> {});
                download.phase = ModelDownload.Phase.READY;
                Onboarding o = new Onboarding(core.engine(), core.settings(), download, (name, chunks) -> () -> {}, () -> true, () -> {});
                o.show(5);
                save(o, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "primeira-execucao-6-escolha");
                // Sem par no design: conferem que nada ficou na cor fixa da canindé num tema claro.
                Theme.apply(Settings.Macaw.LEAR, true);
                core.settings().save(core.settings().load().withMacaw(Settings.Macaw.LEAR).withLightMode(true));
                Onboarding light = new Onboarding(core.engine(), core.settings(), download, (name, chunks) -> () -> {}, () -> true, () -> {});
                light.show(3);
                save(light, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "extra-primeira-execucao-4-atalho-lear-claro");
                light.show(5);
                save(light, Onboarding.WIDTH, Onboarding.HEIGHT, dir + "extra-primeira-execucao-6-lear-claro");
                UpdateDialog.Actions none = new UpdateDialog.Actions() {
                    public void install() {}
                    public void later() {}
                    public void skip() {}
                    public void cancel() {}
                    public void site() {}
                };
                UpdateDialog d = new UpdateDialog(Main.DEMO_UPDATE, "0.2.0", none);
                write(dialog(d), dir + "extra-atualizacao-aviso-lear-claro");
                d.show(UpdateDialog.State.OFFLINE);
                write(dialog(d), dir + "extra-atualizacao-erro-lear-claro");
                Theme.apply(Settings.Macaw.VERMELHA, true);
                view.show(1);
                write(paint(main, view), dir + "extra-historico-vermelha-claro");
                view.show(2);
                write(paint(main, view), dir + "extra-dicionario-vermelha-claro");
            } finally {
                Theme.apply(Settings.Macaw.CANINDE, false);
                main.dispose();
                pop.dispose();
            }
        });
    }

    private static final String PROMPT = "Cria um endpoint POST /pedidos que valida o CPF, salva no Postgres e escreve os testes.";
    /** Quadro do GIF: 35 ms de relógio por tick, 1 quadro a cada 2 ticks (70 ms, o passo do GIF). */
    private static final int TICK = 35, VW = 600, VH = 390, ESCALA = 2;

    /**
     * Quadros do GIF do README (docs/midia/gerar_gif.py monta): o pop-up de verdade ditando no terminal do Claude Code,
     * prévia cinza virando branca, "Transcrevendo", "Colado!" com o voo e o texto colado. Mais a imagem social do repo.
     */
    @Test
    void desenhaVitrine() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        String dir = "vitrine/";
        new File(DIR, dir + "gif").mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            long[] t = {0};
            RecordingPopup p = new RecordingPopup(new DemoCore().settings());
            p.clock = () -> t[0];
            JFrame pop = offscreen(p.view, p.view.getWidth(), p.view.getHeight());
            String[] words = PROMPT.split(" ");
            int n = 0;
            boolean popup = false, colado = false;
            float alpha = 1;
            try {
                long fim = 1400 + 300L * words.length; // fim da fala; depois, pausa, transcrição e "Colado!"
                for (int tick = 0; t[0] < fim + 4600; tick++, t[0] += TICK) {
                    long ms = t[0];
                    if (ms == 35L * 29) { p.show(DictationState.LISTENING); popup = true; }
                    // Uma palavra a cada 300 ms entra cinza (prévia); a cada 3, as anteriores confirmam e viram brancas.
                    int k = ms >= 1400 ? (int) Math.min(words.length, (ms - 1400) / 300 + 1) : 0;
                    if (k != n) {
                        n = k;
                        int ok = n / 3 * 3;
                        p.onLiveText(String.join(" ", java.util.Arrays.copyOf(words, ok)),
                                String.join(" ", java.util.Arrays.copyOfRange(words, ok, n)));
                    }
                    if (agora(ms, fim)) {
                        p.onLiveText(PROMPT, "");
                        p.show(DictationState.SILENCE);
                    }
                    if (agora(ms, fim + 300)) p.show(DictationState.TRANSCRIBING);
                    if (agora(ms, fim + 1000)) {
                        p.onResult(new HistoryEntry(LocalDateTime.now(), PROMPT));
                        p.show(DictationState.PASTED);
                        colado = true;
                    }
                    if (ms >= fim + 2700) alpha = Math.max(0, 1 - (ms - fim - 2700) / 350f);
                    boolean speaking = ms >= 1400 && ms < fim;
                    p.onLevel(speaking ? 0.8f * (0.35f + 0.65f * Math.abs((float) Math.sin(ms * 0.07))) : 0.03f);
                    if (popup) p.tick();
                    if (tick % 2 != 0) continue;
                    BufferedImage img = new BufferedImage(VW * ESCALA, VH * ESCALA, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.scale(ESCALA, ESCALA);
                    cena(g, ms, colado);
                    if (ms >= 250 && ms < 35L * 29) teclas(g, Math.min(1, (ms - 250) / 150f));
                    if (popup && alpha > 0) {
                        g.setComposite(java.awt.AlphaComposite.SrcOver.derive(alpha));
                        pop.validate();
                        Graphics2D gp = (Graphics2D) g.create(50, 226, p.view.getWidth(), p.view.getHeight());
                        p.view.paint(gp);
                        gp.dispose();
                    }
                    g.dispose();
                    write(img, String.format("%sgif/%03d", dir, tick / 2));
                }
                write(social(), dir + "social-preview");
            } finally {
                pop.dispose();
            }
        });
    }

    private static boolean agora(long ms, long at) {
        return ms >= at && ms < at + TICK;
    }

    /** O terminal do Claude Code, como na demonstração do site: prompt vazio e, depois do "Colado!", o texto inteiro. */
    private static void cena(Graphics2D g, long ms, boolean colado) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(new java.awt.Color(0x0B120D));
        g.fillRect(0, 0, VW, VH);
        java.awt.Font mono = new java.awt.Font("Consolas", java.awt.Font.PLAIN, 12);
        int x = 24, y = 18, w = VW - 48, h = 196;
        g.setColor(new java.awt.Color(0x0C0C0C));
        g.fillRoundRect(x, y, w, h, 14, 14);
        g.setColor(new java.awt.Color(0x1F1F1F));
        g.fillRoundRect(x, y, w, 30, 14, 14);
        g.fillRect(x, y + 16, w, 14);
        g.setColor(new java.awt.Color(0x2A3A2E));
        g.drawRoundRect(x, y, w, h, 14, 14);
        g.setFont(Theme.semibold(11));
        g.setColor(new java.awt.Color(0xCCCCCC));
        g.drawString("Claude Code — C:\\projetos\\loja", x + 14, y + 19);
        g.setColor(new java.awt.Color(0x8A8A8A));
        int bx = x + w - 84, by = y + 15;
        g.drawLine(bx, by, bx + 9, by);
        g.drawRect(bx + 30, by - 5, 9, 9);
        g.drawLine(bx + 60, by - 5, bx + 69, by + 4);
        g.drawLine(bx + 60, by + 4, bx + 69, by - 5);

        g.setFont(mono);
        g.setColor(new java.awt.Color(0xD97757));
        g.drawRoundRect(x + 14, y + 42, 250, 40, 8, 8);
        g.setFont(mono.deriveFont(java.awt.Font.BOLD));
        g.drawString("* Welcome to Claude Code!", x + 26, y + 59);
        g.setFont(mono);
        g.setColor(new java.awt.Color(0x8A8A8A));
        g.drawString("cwd: C:\\projetos\\loja", x + 26, y + 74);

        int py = y + 96, ph = 58;
        g.setColor(new java.awt.Color(0x5A5A5A));
        g.drawRoundRect(x + 14, py, w - 28, ph, 8, 8);
        g.setColor(new java.awt.Color(0x8A8A8A));
        g.drawString(">", x + 26, py + 20);
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tx = x + 40, line = 0, cx = tx;
        boolean caret = ms % 1000 < 550;
        if (colado) {
            g.setColor(new java.awt.Color(0xEEEEEE));
            StringBuilder row = new StringBuilder();
            for (String word : PROMPT.split(" ")) {
                String next = row.isEmpty() ? word : row + " " + word;
                if (fm.stringWidth(next) > w - 70) {
                    g.drawString(row.toString(), tx, py + 20 + line++ * 17);
                    row = new StringBuilder(word);
                } else {
                    row = new StringBuilder(next);
                }
            }
            g.drawString(row.toString(), tx, py + 20 + line * 17);
            cx = tx + fm.stringWidth(row.toString()) + 2;
            // Selo do site: "Colado de uma vez".
            String selo = "Colado de uma vez · " + PROMPT.split(" ").length + " palavras";
            g.setFont(Theme.bold(11));
            int sw = g.getFontMetrics().stringWidth(selo) + 30;
            g.setColor(Theme.LIME);
            g.fillRoundRect(x + w - 14 - sw, py + ph + 10, sw, 22, 22, 22);
            g.setColor(Theme.TEXT_DARK);
            g.fillOval(x + w - 14 - sw + 11, py + ph + 18, 6, 6);
            g.drawString(selo, x + w - 14 - sw + 22, py + ph + 25);
        }
        if (caret) {
            g.setColor(new java.awt.Color(0xEEEEEE));
            g.fillRect(cx, py + 10 + line * 17, 7, 13);
        }
    }

    /** "Ctrl + Shift + Espaço" no lugar do pop-up, antes de ele abrir. */
    private static void teclas(Graphics2D g, float a) {
        g = (Graphics2D) g.create();
        g.setComposite(java.awt.AlphaComposite.SrcOver.derive(a));
        g.setFont(Theme.bold(16));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String[] keys = {"Ctrl", "Shift", "Espaço"};
        int gap = 30, total = -gap;
        for (String k : keys) total += fm.stringWidth(k) + 32 + gap;
        int x = (VW - total) / 2, y = 280;
        for (int i = 0; i < keys.length; i++) {
            int kw = fm.stringWidth(keys[i]) + 32;
            g.setColor(new java.awt.Color(0x0B140D));
            g.fillRoundRect(x, y + 4, kw, 40, 12, 12);
            g.setColor(Theme.SURFACE);
            g.fillRoundRect(x, y, kw, 40, 12, 12);
            g.setColor(Theme.BORDER);
            g.drawRoundRect(x, y, kw, 40, 12, 12);
            g.setColor(Theme.TEXT);
            g.drawString(keys[i], x + 16, y + 26);
            x += kw;
            if (i < keys.length - 1) {
                g.setColor(Theme.TEXT_SOFT);
                g.drawString("+", x + (gap - fm.stringWidth("+")) / 2, y + 26);
                x += gap;
            }
        }
        g.dispose();
    }

    /** Imagem social do GitHub (1280x640): as 5 araras em pixel art, nome e o que o app faz. */
    private static BufferedImage social() {
        BufferedImage img = new BufferedImage(1280, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new java.awt.Color(0x0F1712));
        g.fillRect(0, 0, 1280, 640);
        g.setColor(Theme.LIME);
        g.setFont(Theme.bold(26));
        g.drawString("GRÁTIS · OPEN SOURCE · 100% LOCAL", 80, 130);
        g.setColor(new java.awt.Color(0xEEF5EC));
        g.setFont(Theme.display(170));
        g.drawString("ARARA", 72, 310);
        g.setFont(Theme.bold(38));
        g.drawString("Ditado por voz em português do Brasil", 80, 380);
        g.setColor(new java.awt.Color(0x9AAE9E));
        g.setFont(Theme.regular(28));
        g.drawString("Fale e o texto aparece onde está o cursor. Sem nuvem.", 80, 428);
        g.setColor(new java.awt.Color(0xEEF5EC));
        g.setFont(Theme.semibold(26));
        g.drawString("Windows e macOS  ·  arara-voice.vercel.app", 80, 548);
        // As 5 araras (31x42, já com o galho) em escala 4: 3 em cima, 2 embaixo.
        Settings.Macaw[] ms = Settings.Macaw.values();
        int s = 4, aw = 31 * s, ah = 42 * s, gap = 20;
        for (int i = 0; i < ms.length; i++) {
            int row = i < 3 ? 0 : 1, col = row == 0 ? i : i - 3;
            int rowW = (row == 0 ? 3 : 2) * aw + (row == 0 ? 2 : 1) * gap;
            int x = 1030 - rowW / 2 + col * (aw + gap), base = row == 0 ? 300 : 560;
            g.drawImage(Arara.read("araras/" + Theme.id(ms[i]) + "/pousada.png"), x, base - ah + 4, aw, ah, null);
        }
        g.dispose();
        return img;
    }

    private static void configuracoes(DemoCore core, String name) {
        MainView v = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
        v.onState(DictationState.PASTED);
        v.show(3);
        save(v, 1100, 776, name);
    }

    /** Janela que nunca aparece, mas existe (Window.getWindows), para a troca de tema alcançar o componente. */
    private static JFrame offscreen(JComponent c, int w, int h) {
        JFrame f = new JFrame();
        f.setUndecorated(true);
        f.setContentPane(c);
        f.addNotify();
        f.setSize(w, h);
        f.validate();
        return f;
    }

    private static BufferedImage paint(JFrame f, JComponent c) {
        f.validate();
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        return img;
    }

    /** O diálogo com a barra de título do design (a do FlatLaf no Windows: 36 px, fundo da superfície, sem ícone). */
    private static BufferedImage dialog(UpdateDialog d) {
        BufferedImage body = render(d, UpdateDialog.WIDTH, d.getPreferredSize().height);
        BufferedImage img = new BufferedImage(UpdateDialog.WIDTH, body.getHeight() + 36, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Ui.smooth(img.getGraphics());
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.SURFACE);
        g.fillRect(0, 0, UpdateDialog.WIDTH, 36);
        g.setFont(Theme.semibold(13));
        g.setColor(Theme.TEXT_SOFT);
        g.drawString("Atualização do Arara", 16, 23);
        g.drawLine(UpdateDialog.WIDTH - 27, 14, UpdateDialog.WIDTH - 19, 22);
        g.drawLine(UpdateDialog.WIDTH - 27, 22, UpdateDialog.WIDTH - 19, 14);
        g.drawImage(body, 0, 36, null);
        g.dispose();
        return img;
    }

    private static void downloading(ModelDownload d, long done) {
        d.phase = ModelDownload.Phase.DOWNLOADING;
        d.done = done;
        d.total = 574_000_000L;
        d.speed = 8_400_000;
    }

    /** Põe o componente num JFrame que nunca aparece, só para ter layout de verdade, e desenha num PNG. */
    private static void save(JComponent c, int w, int h, String name) {
        write(render(c, w, h), name);
    }

    private static BufferedImage render(JComponent c, int w, int h) {
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
        return img;
    }

    private static void write(BufferedImage img, String name) {
        try {
            ImageIO.write(img, "png", new File(DIR, name + ".png"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Pop-up com "faltam ~X s" (com e sem texto ao vivo) e a dica de modelo lento. */
    @Test
    void desenhaFilaLenta() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        new File(DIR, "fila").mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            RecordingPopup p = new RecordingPopup(new DemoCore().settings());
            JFrame pop = offscreen(p.view, p.view.getWidth(), p.view.getHeight());
            p.show(DictationState.TRANSCRIBING);
            p.onPending(14);
            write(paint(pop, p.view), "fila/popup-sem-texto");
            p.onLiveText(ate(14), "");
            write(paint(pop, p.view), "fila/popup-com-texto");
            JPanel tip = SlowModelTip.panel(br.arara.core.Settings.Model.PRECISO, x -> {});
            JFrame f = offscreen(tip, tip.getPreferredSize().width, tip.getPreferredSize().height);
            write(paint(f, tip), "fila/dica");
        });
    }
}
