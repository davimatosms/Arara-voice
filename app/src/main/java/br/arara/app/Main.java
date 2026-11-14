package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.DictationEngine;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

public final class Main {
    public static void main(String[] args) {
        // Único ponto de troca: com o Whisper pronto, vira AraraCore.start(AraraCore.defaultDataDir(), whisper).
        var core = new DemoCore();
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            RecordingPopup popup = new RecordingPopup(core.settings());
            core.engine().addListener(Ui.onEdt(view));
            core.engine().addListener(Ui.onEdt(popup));

            JFrame frame = new JFrame("Arara");
            frame.setIconImage(Arara.IMAGE);
            frame.setContentPane(view);
            frame.setSize(1100, 720);
            frame.setMinimumSize(new java.awt.Dimension(960, 640));
            frame.setLocationRelativeTo(null);
            boolean tray = tray(frame, core.engine());
            frame.setDefaultCloseOperation(tray ? WindowConstants.HIDE_ON_CLOSE : WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }

    /** Ícone na bandeja com Abrir / Sair. Devolve false se o sistema não tem bandeja. */
    private static boolean tray(JFrame frame, DictationEngine engine) {
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
        quit.addActionListener(e -> {
            engine.close();
            System.exit(0);
        });
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
