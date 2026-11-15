package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.AraraCore;
import br.arara.core.DictationEngine;
import br.arara.core.DictationListener;
import br.arara.core.DictionaryStore;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;
import br.arara.core.UpdateChecker;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.desktop.AppReopenedListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Abre o app. Com {@code --demo}, usa dados falsos em memória em vez do backend real; com {@code --demo --primeira-execucao},
 * mostra a primeira execução com um download de mentira; com {@code --demo --atualizacao}, avisa de uma versão de mentira.
 */
public final class Main {
    /** Lock de cópia única; campo estático para não ser coletado enquanto o app roda. */
    private static SingleInstance instance;

    public static void main(String[] args) {
        boolean demo = List.of(args).contains("--demo");
        // Antes do atalho global e do core: duas cópias na mesma pasta colavam cada ditado duas vezes.
        if (!demo) {
            Path dir = AraraCore.defaultDataDir();
            try {
                instance = SingleInstance.acquire(dir);
                if (instance == null) {
                    alreadyOpen(SingleInstance.notifyFirst(dir));
                    return;
                }
            } catch (IOException e) {
                // sem como travar (pasta sem permissão?): abre assim mesmo, e o core avisa se a pasta não servir
            }
        }
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
            Thread motion = new Thread(() -> popup.live.reduceMotion = Ui.reducedMotion(), "arara-movimento");
            motion.setDaemon(true);
            motion.start();
            download.start(); // já na tela de boas-vindas, em segundo plano

            JFrame frame = new JFrame("Arara");
            frame.setIconImage(Arara.IMAGE);
            frame.setMinimumSize(new java.awt.Dimension(960, 640));
            if (firstRun) {
                DictationListener[] listener = new DictationListener[1];
                Onboarding onboarding = new Onboarding(engine, settings, download, AraraCore::listen,
                        core != null ? core::retryHotkey : () -> true, () -> {
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
            if (instance != null) instance.onShow = () -> SwingUtilities.invokeLater(() -> show(frame));
            TrayIcon tray = tray(frame);
            frame.setDefaultCloseOperation(tray != null ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
            // Atualização: só o app de verdade consulta o site; o --demo usa uma versão de mentira para ver as telas.
            UpdateChecker checker = core != null ? UpdateChecker.system(core.settings()) : null;
            UpdateUi updates = new UpdateUi(frame, checker != null ? UpdateUi.of(checker) : demoUpdates(), tray,
                    checker != null ? () -> System.exit(0) : () -> {});
            view.settingsPage().updates(updates);
            engine.addListener(Ui.onEdt(updates));
            if (tray != null) tray.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                if (!updates.notificationClicked()) show(frame); // duplo clique
            }));
            frame.setVisible(true);
            if (checker != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(checker::close));
                checker.start(u -> SwingUtilities.invokeLater(() -> updates.offer(u)));
            } else if (List.of(args).contains("--atualizacao")) {
                Timer later = new Timer(1500, e -> updates.offer(DEMO_UPDATE));
                later.setRepeats(false);
                later.start();
            }
            if (core != null && !firstRun && !core.retryHotkey()) askAccessibility(frame, core);
        });
    }

    /**
     * Esta é a segunda cópia: aviso curto no canto da tela, sem diálogo que trave, e sai com código 0. Se não
     * conseguiu avisar a primeira para abrir a janela, diz onde encontrar o Arara.
     */
    private static void alreadyOpen(boolean notified) {
        if (GraphicsEnvironment.isHeadless()) System.exit(0);
        String where = AraraCore.MAC ? "na barra de menus" : "na bandeja, perto do relógio";
        String text = notified ? "O Arara já está aberto" : "O Arara já está aberto. Procure o ícone " + where + ".";
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            JLabel label = new JLabel(text);
            label.setFont(Theme.semibold(14f));
            label.setForeground(Theme.TEXT);
            label.setOpaque(true);
            label.setBackground(Theme.SURFACE);
            label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(14, 20, 14, 20)));
            JWindow toast = new JWindow();
            toast.setContentPane(label);
            toast.setAlwaysOnTop(true);
            toast.setFocusableWindowState(false);
            toast.pack();
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            toast.setLocation(screen.x + (screen.width - toast.getWidth()) / 2, screen.y + screen.height - toast.getHeight() - 48);
            toast.setVisible(true);
            new Timer(3000, e -> System.exit(0)).start();
        });
    }

    /** Mostra a janela principal (bandeja, Dock, ou outra cópia que tentou abrir). */
    private static void show(JFrame frame) {
        frame.setVisible(true);
        frame.setExtendedState(JFrame.NORMAL);
        frame.toFront();
    }

    /** macOS sem Acessibilidade (a primeira execução tem um passo próprio para isso): explica e tenta de novo na volta. */
    private static void askAccessibility(JFrame frame, AraraCore core) {
        Object[] options = {"Abrir Ajustes", "Agora não"};
        int choice = JOptionPane.showOptionDialog(frame,
                "Para o atalho e o colar funcionarem, ligue o Arara em\n"
                        + "Ajustes do Sistema › Privacidade e Segurança › Acessibilidade.",
                "Arara", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice != 0) return;
        Ui.openSystemSettings("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility");
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (core.retryHotkey()) frame.removeWindowFocusListener(this);
            }
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

    static final UpdateChecker.Update DEMO_UPDATE = new UpdateChecker.Update("0.3.0", "2026-12-01",
            "Texto ao vivo no pop-up enquanto você fala\nDitado até 2x mais rápido em frases longas\n"
                    + "Correção: acentos no Bloco de Notas",
            URI.create("https://github.com/davimatosms/Arara-voice/releases/download/v0.3.0/Arara-Setup-0.3.0.exe"), "0".repeat(64));

    /** Atualização de mentira para o {@code --demo}: acha a 0.3.0 e "baixa" 86 MB a ~3 MB/s, sem instalar nada. */
    static UpdateUi.Updates demoUpdates() {
        return new UpdateUi.Updates() {
            public String current() { return "0.2.0"; }

            public Optional<UpdateChecker.Update> latest() throws IOException {
                sleep(1200);
                return Optional.of(DEMO_UPDATE);
            }

            public boolean install(UpdateChecker.Update u, AraraCore.DownloadProgress progress,
                    java.util.function.BooleanSupplier cancelled) throws IOException {
                long total = 86_000_000L;
                for (long done = 0; done < total; done += 320_000) {
                    if (cancelled.getAsBoolean()) return false;
                    progress.update(done, total);
                    sleep(100);
                }
                progress.update(total, total);
                return true;
            }

            public void skip(UpdateChecker.Update u) {}

            private void sleep(long ms) throws IOException {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        };
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

    /** Ícone na bandeja (barra de menus no macOS) com Abrir / Sair. Devolve null se o sistema não tem bandeja. */
    private static TrayIcon tray(JFrame frame) {
        if (!SystemTray.isSupported()) return null;
        Runnable open = () -> show(frame);
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
        // macOS: clicar no ícone do Dock com a janela escondida também abre
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED))
            Desktop.getDesktop().addAppEventListener((AppReopenedListener) e -> SwingUtilities.invokeLater(open));
        try {
            SystemTray.getSystemTray().add(icon);
            return icon;
        } catch (AWTException e) {
            return null;
        }
    }
}
