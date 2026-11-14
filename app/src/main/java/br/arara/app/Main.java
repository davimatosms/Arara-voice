package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.AraraCore;
import br.arara.core.DictationEngine;
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
import java.util.List;

/** Abre o app. Com {@code --demo}, usa dados falsos em memória em vez do backend real. */
public final class Main {
    public static void main(String[] args) {
        boolean demo = List.of(args).contains("--demo");
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

            MainView[] viewRef = new MainView[1];
            AraraCore core = real;
            if (core != null) settings = reloadModelOnChange(settings, () -> prepareModel(core, viewRef[0]));
            MainView view = new MainView(engine, history, dictionary, settings);
            viewRef[0] = view;
            RecordingPopup popup = new RecordingPopup(settings);
            engine.addListener(Ui.onEdt(view));
            engine.addListener(Ui.onEdt(popup));
            if (core != null) prepareModel(core, view);

            JFrame frame = new JFrame("Arara");
            frame.setIconImage(Arara.IMAGE);
            frame.setContentPane(view);
            frame.setSize(1100, 720);
            frame.setMinimumSize(new java.awt.Dimension(960, 640));
            frame.setLocationRelativeTo(null);
            boolean tray = tray(frame);
            frame.setDefaultCloseOperation(tray ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }

    /** Baixa/carrega o modelo fora da EDT e mostra o progresso no cartão do modelo. */
    private static void prepareModel(AraraCore core, MainView view) {
        view.modelStatus("Carregando", Theme.YELLOW);
        Thread t = new Thread(() -> {
            try {
                core.prepareModel(p -> SwingUtilities.invokeLater(
                        () -> view.modelStatus("Baixando " + Math.round(p * 100) + "%", Theme.YELLOW)));
                SwingUtilities.invokeLater(() -> view.modelStatus("Pronto", Theme.GREEN));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> view.modelStatus("Erro no modelo", Theme.YELLOW));
            }
        }, "arara-modelo");
        t.setDaemon(true);
        t.start();
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
