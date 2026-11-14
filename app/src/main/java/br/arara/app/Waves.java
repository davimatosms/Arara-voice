package br.arara.app;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Ondinhas de som: barras verdes que andam com o nível do microfone. Tracejado = silêncio. */
final class Waves extends JComponent {
    private final float[] levels;
    private final int bar, gap;
    boolean dashed;

    Waves(int count, int bar, int gap, int height) {
        this.levels = new float[count];
        this.bar = bar;
        this.gap = gap;
        setOpaque(false);
        setPreferredSize(new Dimension(count * (bar + gap), height));
        // Desenho parado parecido com o do design, até chegar o primeiro nível.
        for (int i = 0; i < count; i++) levels[i] = 0.25f + 0.6f * Math.abs((float) Math.sin(i * 1.9));
    }

    void push(float level) {
        System.arraycopy(levels, 1, levels, 0, levels.length - 1);
        levels[levels.length - 1] = Math.max(0f, Math.min(1f, level));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int step = bar + gap, x0 = (getWidth() - levels.length * step + gap) / 2, mid = getHeight() / 2;
        if (dashed) {
            g2.setColor(Theme.alpha(Theme.TEXT_SOFT, 90));
            for (int x = x0; x + bar * 2 <= x0 + levels.length * step; x += bar * 3) {
                g2.fillRoundRect(x, mid - 1, bar * 2, 2, 2, 2);
            }
        } else {
            g2.setColor(Theme.GREEN);
            for (int i = 0; i < levels.length; i++) {
                int h = Math.max(bar, Math.round(levels[i] * getHeight()));
                g2.fillRoundRect(x0 + i * step, mid - h / 2, bar, h, bar, bar);
            }
        }
        g2.dispose();
    }
}
