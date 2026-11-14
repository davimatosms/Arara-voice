package br.arara.app;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;

/** A arara pixel art (31x42), ampliada só em escala inteira e sem suavizar. */
final class Arara extends JComponent {
    static final BufferedImage IMAGE = read();

    private final int scale;
    /** Deslocamento vertical em pixels do sprite (negativo = pulinho), para manter a grade. */
    int hop;
    /** "?" (silêncio), "..." (transcrevendo) ou null. */
    String mark;

    Arara(int scale) {
        this.scale = scale;
        setOpaque(false);
        setPreferredSize(new Dimension(IMAGE.getWidth() * scale + 12 * scale, IMAGE.getHeight() * scale));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int w = IMAGE.getWidth() * scale, h = IMAGE.getHeight() * scale;
        int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2 + hop * scale;
        g2.drawImage(IMAGE, x, y, w, h, null);
        g2.setColor(Theme.YELLOW);
        if ("?".equals(mark)) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(Theme.extrabold(11f * scale));
            g2.drawString("?", x + w + scale, y + 8 * scale);
        } else if ("...".equals(mark)) {
            for (int i = 0; i < 3; i++) g2.fillRect(x + w + i * 3 * scale, y + 2 * scale, 2 * scale, 2 * scale);
        }
        g2.dispose();
    }

    private static BufferedImage read() {
        try {
            return ImageIO.read(Arara.class.getResource("arara.png"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
