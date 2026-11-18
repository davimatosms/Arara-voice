package br.arara.app;

import br.arara.core.Uninstaller;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Locale;

/** Diálogo "Desinstalar o Arara?" (design 22) e a despedida do Mac e do Linux. Só desenha; {@link #open} cuida do fluxo. */
final class UninstallDialog extends JPanel {
    static final int WIDTH = 520, INNER = WIDTH - 56;

    final JCheckBox data = new JCheckBox();
    final JButton cancel = Ui.button("Cancelar", null, Ui.Kind.OUTLINE);
    final JButton ok = Ui.button("Desinstalar", null, Ui.Kind.PRIMARY);

    UninstallDialog(Uninstaller.Os os, String dataPath) {
        super(new BorderLayout());
        setBackground(Theme.BG);
        setBorder(new EmptyBorder(28, 28, 24, 28));
        String intro = switch (os) {
            case MAC -> "O Arara vai para a Lixeira. Como o Mac não tem desinstalador, o Arara cuida da limpeza antes de sair.";
            case WINDOWS -> "O desinstalador do Windows vai abrir para terminar o serviço. Ele guarda seus dados de propósito.";
            case LINUX -> "O AppImage será apagado.";
        };
        data.setText("Apagar também modelos de voz, histórico e dicionário");
        data.setFont(Theme.regular(14));
        data.setForeground(Theme.TEXT);
        data.setOpaque(false);
        data.setFocusPainted(false);
        Ui.Round box = new Ui.Round(new BorderLayout(), Theme.SURFACE, Theme.BORDER, 16);
        box.setBorder(new EmptyBorder(14, 14, 14, 14));
        box.add(data);

        JPanel buttons = Ui.row(10, cancel, ok);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(buttons, BorderLayout.EAST);

        JPanel col = Ui.column(
                Ui.label("Desinstalar o Arara?", Theme.bold(24), Theme.TEXT), Ui.gap(10),
                wrap(intro, Theme.regular(14), Theme.TEXT_SOFT), Ui.gap(22),
                Ui.label("O \"Abrir ao iniciar\" é removido sempre.", Theme.semibold(14), Theme.TEXT), Ui.gap(12),
                box, Ui.gap(10),
                wrap("Desmarcado, seus dados ficam em " + dataPath + ", caso você volte um dia.", Theme.regular(12), Theme.TEXT_SOFT),
                Ui.gap(22));
        box.setAlignmentX(0f);
        add(col);
        add(south, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(WIDTH, getPreferredSize().height));
    }

    /** Mostra o tamanho na opção (omitido se não deu para calcular). */
    void size(OptionalLong bytes) {
        String base = "Apagar também modelos de voz, histórico e dicionário";
        data.setText(bytes.isPresent() ? base + " (" + gb(bytes.getAsLong()) + ")" : base);
    }

    static String gb(long bytes) {
        double g = bytes / 1e9;
        return g >= 0.1 ? String.format(new Locale("pt", "BR"), "%.1f GB", g)
                : String.format(new Locale("pt", "BR"), "%.0f MB", Math.max(1, bytes / 1e6));
    }

    /** Texto que quebra linha: JTextArea mede e desenha com a mesma fonte (o JLabel em HTML cortava a borda). */
    private static JComponent wrap(String text, java.awt.Font font, java.awt.Color color) {
        return wrap(text, font, color, INNER);
    }

    private static JComponent wrap(String text, java.awt.Font font, java.awt.Color color, int width) {
        javax.swing.JTextArea t = new javax.swing.JTextArea(text);
        t.setFont(font);
        t.setForeground(color);
        t.setOpaque(false);
        t.setEditable(false);
        t.setFocusable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(null);
        t.setSize(width, 1);
        t.setPreferredSize(new Dimension(width, t.getPreferredSize().height));
        t.setMaximumSize(t.getPreferredSize());
        t.setAlignmentX(0f);
        return t;
    }

    /** Botão terracota suave do cartão "Desinstalar" das Configurações. */
    static JButton button() {
        JButton b = Ui.button("Desinstalar o Arara…", null, Ui.Kind.OUTLINE);
        b.setForeground(Theme.TERRACOTA);
        Theme.style(b, () -> String.format(
                "arc:999; borderWidth:1; focusWidth:0; innerFocusWidth:2; foreground:%s; hoverBackground:%s; pressedBackground:%s;"
                        + " focusedBackground:%s; borderColor:%s; hoverBorderColor:%s; focusedBorderColor:%s",
                Theme.hex(Theme.TERRACOTA), Theme.hex(Theme.SURFACE), Theme.hex(Theme.SURFACE), Theme.hex(Theme.BG),
                Theme.hex(Theme.TERRACOTA), Theme.hex(Theme.TERRACOTA), Theme.hex(Theme.TEXT)));
        return b;
    }

    // --- despedida ---

    /** Arara de asas abertas (voo_1) em escala 6, "Pronto. O Arara foi removido." e o botão Fechar. */
    static JPanel farewell(boolean mac, String note, Runnable close) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.BG);
        p.setBorder(new EmptyBorder(32, 28, 24, 28));
        BufferedImage sprite = Arara.VOO[1];
        int scale = 6;
        JComponent bird = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(sprite, (getWidth() - sprite.getWidth() * scale) / 2, 0, sprite.getWidth() * scale,
                        sprite.getHeight() * scale, null);
                g2.dispose();
            }
        };
        bird.setPreferredSize(new Dimension(WIDTH - 56, sprite.getHeight() * scale));
        JLabel title = Ui.label("Pronto. O Arara foi removido.", Theme.bold(22), Theme.TEXT);
        JLabel sub = Ui.label("Obrigado por ter usado. A porta fica aberta.", Theme.regular(14), Theme.TEXT_SOFT);
        title.setAlignmentX(0.5f);
        sub.setAlignmentX(0.5f);
        JPanel col = Ui.column(bird, Ui.gap(24), title, Ui.gap(6), sub);
        bird.setAlignmentX(0.5f);
        String text = mac ? (note == null ? "" : note + "\n\n")
                + "Opcional no Mac: para tirar as permissões, abra Ajustes do Sistema > Privacidade e Segurança e remova o Arara de Microfone e Acessibilidade."
                : note;
        if (text != null && !text.isBlank()) {
            col.add(Ui.gap(18));
            Ui.Round n = new Ui.Round(new BorderLayout(), Theme.SURFACE, Theme.BORDER, 14);
            n.setBorder(new EmptyBorder(12, 14, 12, 14));
            n.add(wrap(text.replace("\n\n", " "), Theme.regular(12), Theme.TEXT_SOFT));
            n.setAlignmentX(0.5f);
            col.add(n);
        }
        for (Component c : col.getComponents()) if (c instanceof JComponent j) j.setAlignmentX(0.5f);
        JButton fechar = Ui.button("Fechar", null, Ui.Kind.PRIMARY);
        fechar.addActionListener(e -> close.run());
        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setBorder(new EmptyBorder(22, 0, 0, 0));
        south.add(fechar);
        p.add(col);
        p.add(south, BorderLayout.SOUTH);
        p.putClientProperty("fechar", fechar);
        return p;
    }

    // --- fluxo ---

    static String display(Path dir) {
        String appData = System.getenv("APPDATA"), home = System.getProperty("user.home");
        String s = dir.toString();
        if (appData != null && !appData.isBlank() && s.startsWith(appData)) return "%APPDATA%" + s.substring(appData.length());
        if (home != null && s.startsWith(home)) return "~" + s.substring(home.length()).replace('\\', '/');
        return s;
    }

    static void open(Component parent) {
        if (Main.demo) {
            JOptionPane.showMessageDialog(parent, "Modo demo: nada é removido.", "Desinstalar o Arara", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Uninstaller u = Uninstaller.system(Main::release);
        Uninstaller.Os os = br.arara.core.AraraCore.MAC ? Uninstaller.Os.MAC
                : br.arara.core.AraraCore.LINUX ? Uninstaller.Os.LINUX : Uninstaller.Os.WINDOWS;
        UninstallDialog d = new UninstallDialog(os, display(u.dataDir()));
        JDialog w = new JDialog(SwingUtilities.getWindowAncestor(parent), "Desinstalar o Arara", java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
        UpdateUi.decorate(w.getRootPane());
        w.setContentPane(d);
        w.setResizable(false);
        w.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        w.getRootPane().setDefaultButton(d.cancel); // Enter = Cancelar (padrão seguro)
        w.getRootPane().registerKeyboardAction(e -> w.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.cancel.addActionListener(e -> w.dispose());
        new SwingWorker<OptionalLong, Void>() {
            @Override
            protected OptionalLong doInBackground() {
                return u.dataSize();
            }

            @Override
            protected void done() {
                try {
                    d.size(get());
                } catch (Exception ignored) {
                    // sem o número: a opção fica sem tamanho
                }
                w.pack();
            }
        }.execute();
        d.ok.addActionListener(e -> {
            boolean wipe = d.data.isSelected();
            w.dispose();
            new SwingWorker<Uninstaller.Outcome, Void>() {
                @Override
                protected Uninstaller.Outcome doInBackground() {
                    return u.run(wipe);
                }

                @Override
                protected void done() {
                    try {
                        finish(parent, os, get());
                    } catch (Exception ex) {
                        System.exit(0);
                    }
                }
            }.execute();
        });
        w.pack();
        w.setLocationRelativeTo(parent);
        w.setVisible(true);
    }

    private static void finish(Component parent, Uninstaller.Os os, Uninstaller.Outcome o) {
        switch (o.step()) {
            case EXIT -> System.exit(0);
            case MESSAGE -> {
                JOptionPane.showMessageDialog(parent, o.message(), "Desinstalar o Arara", JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
            }
            case FAREWELL -> {
                JDialog w = new JDialog((java.awt.Window) null, "Arara", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
                UpdateUi.decorate(w.getRootPane());
                w.setContentPane(farewell(os == Uninstaller.Os.MAC, o.message(), () -> System.exit(0)));
                w.setResizable(false);
                w.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                w.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        System.exit(0);
                    }
                });
                w.pack();
                w.setLocationRelativeTo(null);
                w.setVisible(true);
            }
        }
    }
}
