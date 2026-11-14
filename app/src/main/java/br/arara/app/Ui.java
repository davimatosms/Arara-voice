package br.arara.app;

import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.HistoryEntry;
import br.arara.core.Settings;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Peças visuais do design V1: botões em pílula, cartões, selos, teclas, chave liga/desliga. */
final class Ui {
    enum Kind { PRIMARY, SECONDARY, OUTLINE }

    private Ui() {}

    static JLabel label(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    /** Rótulo pequeno em caixa alta, como "ATALHO" e "TEXTO". */
    static JLabel caption(String text) {
        return label(text, Theme.bold(11), Theme.TEXT_SOFT);
    }

    static FlatSVGIcon icon(String name, int size, Color color) {
        return icon(name, size, () -> color);
    }

    /** Ícone Lucide; a cor é lida a cada pintura, então pode depender do estado do botão. */
    static FlatSVGIcon icon(String name, int size, Supplier<Color> color) {
        FlatSVGIcon icon = new FlatSVGIcon("br/arara/app/icons/" + name + ".svg", size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color.get()));
        return icon;
    }

    static JButton button(String text, String iconName, Kind kind) {
        Color bg = switch (kind) {
            case PRIMARY -> Theme.GREEN;
            case SECONDARY -> Theme.BLUE;
            case OUTLINE -> Theme.BG;
        };
        Color fg = kind == Kind.PRIMARY ? Theme.TEXT_DARK : Theme.TEXT;
        Color hover = switch (kind) {
            case PRIMARY -> Theme.GREEN_LIGHT;
            case SECONDARY -> Theme.BLUE.brighter();
            case OUTLINE -> Theme.SURFACE;
        };
        Color border = kind == Kind.OUTLINE ? Theme.BORDER : bg;
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFont(Theme.bold(14));
        b.setMargin(new Insets(9, 20, 9, 22));
        b.putClientProperty(FlatClientProperties.STYLE, String.format(
                "arc:999; borderWidth:1; focusWidth:0; innerFocusWidth:2; hoverBackground:%s; pressedBackground:%s;"
                        + " focusedBackground:%s; borderColor:%s; hoverBorderColor:%s; focusedBorderColor:%s",
                Theme.hex(hover), Theme.hex(hover), Theme.hex(bg), Theme.hex(border), Theme.hex(border),
                Theme.hex(Theme.TEXT)));
        if (iconName != null) {
            b.setIcon(icon(iconName, 18, fg));
            b.setIconTextGap(10);
        }
        return b;
    }

    /** Botão só de ícone: o nome acessível é obrigatório. */
    static JButton iconButton(String iconName, String accessibleName) {
        JButton b = new JButton(icon(iconName, 20, Theme.TEXT_SOFT));
        b.setToolTipText(accessibleName);
        b.getAccessibleContext().setAccessibleName(accessibleName);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setFocusable(true);
        b.setMargin(new Insets(6, 6, 6, 6));
        return b;
    }

    static JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        f.getAccessibleContext().setAccessibleName(placeholder);
        f.setFont(Theme.regular(14));
        f.setMargin(new Insets(9, 16, 9, 16));
        return f;
    }

    /** Coluna vertical alinhada à esquerda. */
    static JPanel column(Component... children) {
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (Component c : children) {
            if (c instanceof JComponent j) j.setAlignmentX(0f);
            p.add(c);
        }
        return p;
    }

    static Component gap(int height) {
        return Box.createVerticalStrut(height);
    }

    /** Linha horizontal com espaço só entre os itens (o FlowLayout põe espaço também na borda). */
    static JPanel row(int hgap, Component... children) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, hgap, 0));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, -hgap, 0, -hgap));
        for (Component c : children) p.add(c);
        return p;
    }

    /** Rolagem só na vertical: o conteúdo acompanha a largura da janela. */
    static JScrollPane scroll(JComponent content) {
        class Track extends JPanel implements Scrollable {
            Track() {
                super(new BorderLayout());
                setOpaque(false);
                add(content, BorderLayout.NORTH);
            }

            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
            public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return r.height; }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() { return false; }
        }
        JScrollPane s = new JScrollPane(new Track());
        s.setBorder(null);
        s.setOpaque(false);
        s.getViewport().setOpaque(false);
        return s;
    }

    /** Centraliza na vertical, encostado à esquerda. */
    static JPanel centerY(Component c) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.add(c, new GridBagConstraints(0, 0, 1, 1, 1, 1, GridBagConstraints.WEST, GridBagConstraints.NONE,
                new Insets(0, 0, 0, 0), 0, 0));
        return p;
    }

    static Round card(LayoutManager layout) {
        Round r = new Round(layout, Theme.SURFACE, Theme.BORDER, 28);
        r.setBorder(new EmptyBorder(22, 24, 22, 24));
        return r;
    }

    static JPanel keycaps(String hotkey) {
        JPanel p = row(6);
        String[] keys = hotkey.split("\\+");
        StringBuilder spoken = new StringBuilder("Atalho:");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) p.add(label("+", Theme.regular(12), Theme.TEXT_SOFT));
            String key = keys[i].equals("Space") ? "Espaço" : keys[i];
            Round cap = new Round(new BorderLayout(), Theme.BG, Theme.BORDER, 12);
            JLabel l = label(key, Theme.bold(13), Theme.TEXT);
            l.setBorder(new EmptyBorder(5, 11, 5, 11));
            cap.add(l);
            p.add(cap);
            spoken.append(' ').append(key);
        }
        p.getAccessibleContext().setAccessibleName(spoken.toString());
        return p;
    }

    /** Chave liga/desliga: um JCheckBox com desenho próprio, então teclado e leitor de tela já funcionam. */
    static JCheckBox toggle(boolean on, String accessibleName, Consumer<Boolean> onChange) {
        JCheckBox cb = new JCheckBox();
        cb.setSelected(on);
        cb.setOpaque(false);
        cb.getAccessibleContext().setAccessibleName(accessibleName);
        Icon icon = new Icon() {
            public int getIconWidth() { return 48; }
            public int getIconHeight() { return 30; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = smooth(g);
                boolean sel = cb.isSelected();
                g2.setColor(sel ? Theme.GREEN : Theme.BORDER);
                g2.fillRoundRect(x + 3, y + 3, 42, 24, 24, 24);
                g2.setColor(sel ? Theme.TEXT_DARK : Theme.TEXT_SOFT);
                g2.fillOval(x + (sel ? 24 : 6), y + 6, 18, 18);
                if (cb.isFocusOwner()) {
                    g2.setColor(Theme.TEXT);
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRoundRect(x + 1, y + 1, 46, 28, 28, 28);
                }
                g2.dispose();
            }
        };
        cb.setIcon(icon);
        cb.setSelectedIcon(icon);
        cb.addActionListener(e -> onChange.accept(cb.isSelected()));
        return cb;
    }

    /** Botões segmentados em pílula (Leve / Equilibrado / Preciso). */
    static JPanel segmented(String[] labels, int selected, String accessibleName, IntConsumer onChange) {
        Round p = new Round(new FlowLayout(FlowLayout.CENTER, 0, 0), Theme.BG, Theme.BORDER, 999);
        p.setBorder(new EmptyBorder(3, 3, 3, 3));
        p.getAccessibleContext().setAccessibleName(accessibleName);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < labels.length; i++) {
            JToggleButton t = new JToggleButton(labels[i], i == selected);
            t.setFont(Theme.semibold(14));
            t.setMargin(new Insets(5, 18, 5, 18));
            t.setBackground(Theme.BG); // sem setForeground: senão o FlatLaf ignora o selectedForeground
            t.putClientProperty(FlatClientProperties.STYLE, String.format(
                    "arc:999; borderWidth:1; focusWidth:0; innerFocusWidth:2; selectedBackground:%s;"
                            + " selectedForeground:%s; hoverBackground:%s; borderColor:#00000000; focusedBorderColor:%s",
                    Theme.hex(Theme.GREEN), Theme.hex(Theme.TEXT_DARK), Theme.hex(Theme.SURFACE),
                    Theme.hex(Theme.TEXT)));
            int index = i;
            t.addActionListener(e -> onChange.accept(index));
            group.add(t);
            p.add(t);
        }
        return p;
    }

    static String modelName(Settings.Model model) {
        return switch (model) {
            case LEVE -> "base";
            case EQUILIBRADO -> "small";
            case PRECISO -> "large-v3-turbo";
        };
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g2;
    }

    /** O motor pode avisar de qualquer thread; a UI só mexe na EDT. */
    static DictationListener onEdt(DictationListener l) {
        return new DictationListener() {
            public void onState(DictationState s) { SwingUtilities.invokeLater(() -> l.onState(s)); }
            public void onLevel(float level) { SwingUtilities.invokeLater(() -> l.onLevel(level)); }
            public void onResult(HistoryEntry e) { SwingUtilities.invokeLater(() -> l.onResult(e)); }
            public void onError(String msg) { SwingUtilities.invokeLater(() -> l.onError(msg)); }
        };
    }

    /** Painel com cantos arredondados. */
    static class Round extends JPanel {
        private final Color border;
        private final int arc;

        Round(LayoutManager layout, Color bg, Color border, int arc) {
            super(layout);
            setOpaque(false);
            setBackground(bg);
            this.border = border;
            this.arc = arc;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            int a = Math.min(arc, getHeight());
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, a, a);
            if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, a, a);
            }
            g2.dispose();
        }
    }

    /** Selo de estado: pílula com bolinha ("Pronto", "Ouvindo", "Transcrevendo"). */
    static final class Badge extends JLabel {
        private Color color = Theme.GREEN;
        private boolean solid;

        Badge() {
            setFont(Theme.bold(12));
            setBorder(new EmptyBorder(4, 23, 4, 12));
            setHorizontalTextPosition(LEADING);
            setIconTextGap(5);
        }

        Badge(String text, Color color) {
            this();
            set(text, color, false);
        }

        /** solid: fundo cheio com texto branco (só no azul, que tem contraste para isso). */
        Badge set(String text, Color color, boolean solid) {
            this.color = color;
            this.solid = solid;
            setText(text);
            setIcon(null);
            setForeground(solid ? Theme.TEXT : color);
            repaint();
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            int h = getHeight();
            g2.setColor(solid ? color : Theme.alpha(color, 45));
            g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            g2.setColor(getForeground());
            g2.fillOval(11, (h - 7) / 2, 7, 7);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
