package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.AraraCore;
import br.arara.core.DictationEngine;
import br.arara.core.DictationListener;
import br.arara.core.DictionaryStore;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;

/**
 * Abre o app. Com {@code --demo}, usa dados falsos em memória em vez do backend real; com {@code --demo --primeira-execucao},
 * mostra a primeira execução com um download de mentira.
 */
public final class Main {
    public static void main(String[] args) {
        boolean demo = List.of(args).contains("--demo");
        // Primeira execução: ainda não existe config.json (decidido antes de o core criar qualquer coisa).
        boolean firstRun = demo ? List.of(args).contains("--primeira-execucao") : Onboarding.firstRun(AraraCore.defaultDataDir());
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            DictationEngine engine;
            HistoryStore history;
            DictionaryStore dictionary;
            SettingsStore settings;
            AraraCore real = null;
            if (demo) {
                DemoCore core = new DemoCore();
                engine = core.engine();
                history = core.history();
                dictionary = core.dictionary();
                settings = core.settings();
            } else {
                try {
                    real = AraraCore.start(AraraCore.defaultDataDir());
                } catch (RuntimeException e) {
                    JOptionPane.showMessageDialog(null, "O Arara não conseguiu iniciar: " + e.getMessage(), "Arara",
                            JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                    return;
                }
                engine = real.engine();
                history = real.history();
                dictionary = real.dictionary();
                settings = real.settings();
            }
            AutoCloseable closer = demo ? engine : real;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    closer.close();
                } catch (Exception ignored) {
                    // já está saindo
                }
            }));

            AraraCore core = real;
            ModelDownload download = new ModelDownload(core != null ? core::prepareModel : firstRun ? Main::fakeDownload : p -> {});
            settings = reloadModelOnChange(settings, download::start);
            MainView view = new MainView(engine, history, dictionary, settings);
            download.addListener(() -> modelStatus(view, download.phase, download.fraction()));
            RecordingPopup popup = new RecordingPopup(settings);
            engine.addListener(Ui.onEdt(view));
            engine.addListener(Ui.onEdt(popup));
            download.start(); // já na tela de boas-vindas, em segundo plano

            JFrame frame = new JFrame("Arara");
            frame.setIconImage(Arara.IMAGE);
            frame.setMinimumSize(new java.awt.Dimension(960, 640));
            if (firstRun) {
                DictationListener[] listener = new DictationListener[1];
                Onboarding onboarding = new Onboarding(engine, settings, download, AraraCore::listen, () -> {
                    engine.removeListener(listener[0]);
                    frame.setContentPane(view);
                    frame.setResizable(true);
                    frame.setSize(1100, 720);
                    frame.validate();
                    view.refresh();
                });
                listener[0] = Ui.onEdt(onboarding);
                engine.addListener(listener[0]);
                frame.addWindowFocusListener(new WindowAdapter() {
                    @Override public void windowGainedFocus(WindowEvent e) { onboarding.windowFocus(true); }
                    @Override public void windowLostFocus(WindowEvent e) { onboarding.windowFocus(false); }
                });
                frame.setContentPane(onboarding);
                frame.setResizable(false); // posições fixas do design
                frame.pack();
            } else {
                frame.setContentPane(view);
                frame.setSize(1100, 720);
            }
            frame.setLocationRelativeTo(null);
            boolean tray = tray(frame);
            frame.setDefaultCloseOperation(tray ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }

    /** Estado do modelo no cartão da barra lateral da tela principal. */
    private static void modelStatus(MainView view, ModelDownload.Phase phase, double fraction) {
        switch (phase) {
            case LOADING -> view.modelStatus("Carregando", Theme.YELLOW);
            case DOWNLOADING -> view.modelStatus("Baixando " + Math.round(fraction * 100) + "%", Theme.YELLOW);
            case PAUSED -> view.modelStatus("Sem internet", Theme.YELLOW);
            case READY -> view.modelStatus("Pronto", Theme.GREEN);
            case FAILED -> view.modelStatus("Erro no modelo", Theme.YELLOW);
        }
    }

    /** Download de mentira para o {@code --demo --primeira-execucao}: 574 MB a ~20 MB/s. */
    private static void fakeDownload(AraraCore.DownloadProgress progress) throws IOException {
        long total = 574_041_195L;
        try {
            for (long done = 0; done < total; done += 2_000_000) {
                progress.update(done, total);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        progress.update(total, total);
    }

    /** Quando o usuário troca o modelo nas configurações, o backend precisa preparar o novo. */
    private static SettingsStore reloadModelOnChange(SettingsStore store, Runnable onModelChange) {
        return new SettingsStore() {
            public Settings load() { return store.load(); }

            public void save(Settings s) {
                Settings.Model before = store.load().model();
                store.save(s);
                if (s.model() != before) onModelChange.run();
            }
        };
    }

    /** Ícone na bandeja com Abrir / Sair. Devolve false se o sistema não tem bandeja. */
    private static boolean tray(JFrame frame) {
        if (!SystemTray.isSupported()) return false;
        Runnable open = () -> {
            frame.setVisible(true);
            frame.setExtendedState(JFrame.NORMAL);
            frame.toFront();
        };
        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Abrir");
        openItem.addActionListener(e -> SwingUtilities.invokeLater(open));
        MenuItem quit = new MenuItem("Sair");
        quit.addActionListener(e -> System.exit(0)); // o shutdown hook fecha o backend
        menu.add(openItem);
        menu.addSeparator();
        menu.add(quit);
        TrayIcon icon = new TrayIcon(Arara.IMAGE, "Arara", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> SwingUtilities.invokeLater(open)); // duplo clique
        try {
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException e) {
            return false;
        }
    }
}
