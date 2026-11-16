package br.arara.app;

import br.arara.core.Settings;
import br.arara.core.Settings.Macaw;
import br.arara.core.SettingsStore;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Escolha da arara e do modo (Configurações › Aparência e passo 6 da primeira execução). Cada toque salva na hora; quem
 * salva as configurações (Main) troca o tema.
 */
final class Appearance {
    private Appearance() {}

    private static final Map<Macaw, BufferedImage> POUSADA = new EnumMap<>(Macaw.class);
    private static final Map<Macaw, BufferedImage> ICON = new EnumMap<>(Macaw.class);

    static BufferedImage pousada(Macaw m) {
        return POUSADA.computeIfAbsent(m, k -> Arara.read("araras/" + Theme.id(k) + "/pousada.png"));
    }

    /** As 5 araras lado a lado, cada uma nas cores do seu tema no modo atual. */
    static JPanel tiles(SettingsStore settings, int w, int h, int scale, boolean place, int gap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.getAccessibleContext().setAccessibleName("Arara");
        ButtonGroup group = new ButtonGroup();
        Macaw[] all = Macaw.values();
        for (int i = 0; i < all.length; i++) {
            Tile t = new Tile(all[i], scale, place);
            t.setPreferredSize(new Dimension(w, h));
            t.setSelected(settings.load().macaw() == all[i]);
            Macaw m = all[i];
            t.addActionListener(e -> save(settings, x -> x.withMacaw(m)));
            group.add(t);
            if (i > 0) p.add(javax.swing.Box.createHorizontalStrut(gap));
            p.add(t);
        }
        return p;
    }

    /** Chave Escuro/Claro (padrão: Escuro), em pé nas Configurações e deitada na primeira execução. */
    static Ui.Round modes(SettingsStore settings, boolean vertical, int width) {
        Ui.Round box = new Ui.Round(null, Theme.BG, Theme.BORDER, 28);
        box.getAccessibleContext().setAccessibleName("Modo");
        box.setPreferredSize(vertical ? new Dimension(width, 96) : new Dimension(2 * width + 8, 44));
        box.setMaximumSize(box.getPreferredSize());
        ButtonGroup group = new ButtonGroup();
        String[][] options = {{"Escuro", "moon"}, {"Claro", "sun"}};
        for (int i = 0; i < options.length; i++) {
            JToggleButton t = new JToggleButton(options[i][0], settings.load().lightMode() == (i == 1));
            t.setIcon(Ui.icon(options[i][1], 18, () -> t.isSelected() ? Theme.ON_PRIMARY : Theme.TEXT));
            t.setIconTextGap(8);
            t.setFont(Theme.bold(14));
            t.setMargin(new Insets(0, 0, 0, 0));
            t.setBackground(Theme.BG); // sem setForeground: senão o FlatLaf ignora o selectedForeground
            Theme.style(t, () -> String.format("arc:20; borderWidth:0; focusWidth:0; innerFocusWidth:2; selectedBackground:%s;"
                            + " selectedForeground:%s; hoverBackground:%s; borderColor:#00000000; focusedBorderColor:%s",
                    Theme.hex(Theme.PRIMARY), Theme.hex(Theme.ON_PRIMARY), Theme.hex(Theme.SURFACE), Theme.hex(Theme.TEXT)));
            boolean light = i == 1;
            t.addActionListener(e -> save(settings, x -> x.withLightMode(light)));
            if (vertical) t.setBounds(4, 4 + i * 44, width - 8, 40);
            else t.setBounds(4 + i * width, 4, width - 8, 36);
            group.add(t);
            box.add(t);
        }
        return box;
    }

    /** Ícone de 32 px que a bandeja e a janela vão mostrar: a arara escolhida com a chave ligada, senão a canindé. */
    static JComponent iconPreview(SettingsStore settings) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Settings s = settings.load();
                Macaw m = s.macawIcon() ? s.macaw() : Macaw.CANINDE;
                g.drawImage(ICON.computeIfAbsent(m, k -> Arara.read("araras/" + Theme.id(k) + "/icone-32.png")), 0, 0, null);
            }
        };
        c.setPreferredSize(new Dimension(32, 32));
        return c;
    }

    private static void save(SettingsStore settings, UnaryOperator<Settings> change) {
        settings.save(change.apply(settings.load()));
    }

    /** Miniatura: fundo, arara em escala inteira, nome (e onde vive) e as 3 cores do tema; marcada com borda e check. */
    static final class Tile extends JToggleButton {
        private final Macaw macaw;
        private final int scale;
        private final boolean place;

        Tile(Macaw macaw, int scale, boolean place) {
            this.macaw = macaw;
            this.scale = scale;
            this.place = place;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            getAccessibleContext().setAccessibleName(Theme.name(macaw) + (place ? ", " + Theme.place(macaw) : ""));
            setToolTipText(Theme.name(macaw));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme.Palette p = Theme.palette(macaw, Theme.active.light());
            int w = getWidth(), h = getHeight();
            boolean sel = isSelected();
            g2.setColor(new Color(p.bg()));
            g2.fillRoundRect(1, 1, w - 2, h - 2, 32, 32);
            g2.setColor(sel ? Theme.ACTIVE : new Color(p.border()));
            g2.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 32, 32);
            if (isFocusOwner()) {
                g2.setColor(Theme.TEXT);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {3, 3}, 0));
                g2.drawRoundRect(5, 5, w - 11, h - 11, 26, 26);
            }
            BufferedImage img = pousada(macaw);
            int sw = img.getWidth() * scale, sh = img.getHeight() * scale;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(img, (w - sw) / 2, 14, sw, sh, null);
            int y = 14 + sh + 10;
            g2.setFont(Theme.extrabold(scale < 3 ? 13 : 16));
            g2.setColor(new Color(p.text()));
            FontMetrics fm = g2.getFontMetrics();
            y += fm.getAscent();
            String name = Theme.name(macaw);
            g2.drawString(name, (w - fm.stringWidth(name)) / 2, y);
            if (place) {
                g2.setFont(Theme.semibold(12));
                g2.setColor(new Color(p.textSoft()));
                FontMetrics small = g2.getFontMetrics();
                y += 8;
                for (String line : wrap(Theme.place(macaw), small, w - 12)) {
                    y += 16;
                    g2.drawString(line, (w - small.stringWidth(line)) / 2, y);
                }
            }
            int[] colors = {p.primary(), p.accent(), p.support()};
            for (int i = 0; i < colors.length; i++) {
                g2.setColor(new Color(colors[i]));
                g2.fillOval((w - 42) / 2 + i * 16, h - 22, 10, 10);
            }
            if (sel) {
                g2.setColor(Theme.ACTIVE);
                g2.fillOval(w - 30, 8, 22, 22);
                Ui.icon("check", 14, Theme.active.light() ? Color.WHITE : new Color(p.bg())).paintIcon(this, g2, w - 26, 12);
            }
            g2.dispose();
        }

        private static List<String> wrap(String text, FontMetrics fm, int width) {
            List<String> lines = new ArrayList<>();
            String line = "";
            for (String word : text.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(next) > width && !line.isEmpty()) {
                    lines.add(line);
                    line = word;
                } else {
                    line = next;
                }
            }
            lines.add(line);
            return lines;
        }
    }
}
