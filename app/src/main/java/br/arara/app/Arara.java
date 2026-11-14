package br.arara.app;

import br.arara.core.DictationState;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A arara pixel art (31x42), ampliada só em escala inteira e sem suavizar. */
final class Arara extends JComponent {
    /** Pousada de bico fechado (é também o {@code fala_0} de arte/sprites). */
    static final BufferedImage IMAGE = read("arara.png");
    /** Bico fechado, entreaberto e aberto. */
    static final BufferedImage[] FALA = {IMAGE, read("sprites/fala_1.png"), read("sprites/fala_2.png")};
    static final BufferedImage PISCAR = read("sprites/piscar.png");
    /** Ciclo de bater de asas, 81x58, sem galho. */
    static final BufferedImage[] VOO = new BufferedImage[6];
    static {
        for (int i = 0; i < VOO.length; i++) VOO[i] = read("sprites/voo_" + i + ".png");
    }
    private static final Color[] NOTE_COLORS = {Theme.GREEN, Theme.GREEN_LIGHT, Theme.YELLOW};
    /** Vida de uma notinha em ticks de 33 ms (~0,6 s). */
    static final int NOTE_LIFE = 18;
    /** Ticks por quadro de voo (~100 ms) e ciclos no "Colado!". */
    static final int FLY_TICKS = 3, FLY_CYCLES = 2;

    private final int scale;
    /** Deslocamento vertical em pixels do sprite (negativo = pulinho), para manter a grade. */
    int hop;
    /** "?" (silêncio), "..." (transcrevendo) ou null. */
    String mark;
    /** Quadro desenhado agora; os maiores que o pousado (voo) ficam centralizados. */
    BufferedImage sprite = IMAGE;
    /** 0 fechado, 1 entreaberto, 2 aberto. */
    int beak;
    /** Volume suavizado que o bico segue. */
    float voice;
    /** Opacidade (microfone bloqueado na primeira execução deixa a arara apagada). */
    float alpha = 1f;
    private int blinkIn = nextBlink(), blinkLeft;
    /** Notinhas em pixels do sprite pousado: {x, y, idade, lado (-1 ou 1), cor}. */
    final List<int[]> notes = new ArrayList<>();

    Arara(int scale) {
        this.scale = scale;
        setOpaque(false);
        setPreferredSize(new Dimension(IMAGE.getWidth() * scale + 12 * scale, IMAGE.getHeight() * scale));
    }

    /** Bico com histerese: só abre acima de um limiar e só fecha bem abaixo dele, para não tremer. */
    static int beakFor(int current, float voice) {
        return switch (current) {
            case 0 -> voice > 0.62f ? 2 : voice > 0.45f ? 1 : 0;
            case 1 -> voice > 0.62f ? 2 : voice < 0.38f ? 0 : 1;
            default -> voice < 0.38f ? 0 : voice < 0.54f ? 1 : 2;
        };
    }

    /** Um passo da animação do pop-up (timer de 33 ms); {@code tick} conta desde a última troca de estado. */
    void animate(DictationState state, float level, int tick) {
        hop = 0;
        if (state == DictationState.LISTENING) {
            voice += (level - voice) * 0.4f;
            beak = beakFor(beak, voice);
            // Notinhas: no máximo 4 no ar, uma a cada 6 ticks, alternando os lados do bico.
            if (beak == 2 && notes.size() < 4 && tick % 6 == 0) {
                int side = (tick / 6) % 2 == 0 ? 1 : -1;
                notes.add(new int[] {side > 0 ? 27 : 1, 13, 0, side, ThreadLocalRandom.current().nextInt(NOTE_COLORS.length)});
            }
            if (beak == 2 && (tick / 4) % 2 == 0) hop = -1;
        } else {
            voice = 0;
            beak = 0;
        }
        stepNotes();
        if (state == DictationState.TRANSCRIBING) hop = (tick / 12) % 2 == 0 ? -1 : 0;
        if (state == DictationState.PASTED && tick < VOO.length * FLY_TICKS * FLY_CYCLES) {
            sprite = VOO[(tick / FLY_TICKS) % VOO.length];
            hop = -1; // sobe um pouco sem cortar as pontas das asas no alto do quadro
            return;
        }
        if (blinkLeft > 0) blinkLeft--;
        else if (--blinkIn <= 0) {
            blinkLeft = 2 + ThreadLocalRandom.current().nextInt(2); // 2-3 ticks de olho fechado
            blinkIn = nextBlink();
        }
        sprite = blinkLeft > 0 ? PISCAR : FALA[beak];
    }

    /** Notinhas sobem 1 px a cada 2 ticks, se afastam do bico 1 px a cada 3 e somem no fim da vida. */
    void stepNotes() {
        notes.removeIf(n -> ++n[2] >= NOTE_LIFE);
        for (int[] n : notes) {
            if (n[2] % 2 == 0) n[1]--;
            if (n[2] % 3 == 0) n[0] += n[3];
        }
    }

    /** Volta ao pousado de bico fechado (pop-up escondido ou estado novo). */
    void reset() {
        sprite = IMAGE;
        beak = 0;
        voice = 0;
        hop = 0;
        notes.clear();
    }

    private static int nextBlink() {
        return ThreadLocalRandom.current().nextInt(90, 151); // 3-5 s
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(alpha));
        int w = IMAGE.getWidth() * scale, h = IMAGE.getHeight() * scale;
        int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2 + hop * scale;
        int sw = sprite.getWidth() * scale, sh = sprite.getHeight() * scale;
        // Quadros de voo são maiores: centralizados no lugar do pousado, deslocados em pixels inteiros do sprite.
        g2.drawImage(sprite, x - (sprite.getWidth() - IMAGE.getWidth()) / 2 * scale,
                y - (sprite.getHeight() - IMAGE.getHeight()) / 2 * scale, sw, sh, null);
        for (int[] n : notes) {
            g2.setColor(NOTE_COLORS[n[4]]);
            int nx = x + n[0] * scale, ny = y + n[1] * scale, p = scale;
            if (n[2] < NOTE_LIFE - 4) {
                // colcheia de 3x4: cabeça 2x2, haste e bandeirinha
                g2.fillRect(nx, ny + 2 * p, 2 * p, 2 * p);
                g2.fillRect(nx + p, ny, p, 2 * p);
                g2.fillRect(nx + 2 * p, ny, p, p);
            } else {
                g2.fillRect(nx, ny + 2 * p, p, p); // sumindo: vira um pixel só
            }
        }
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

    static BufferedImage read(String name) {
        try {
            return ImageIO.read(Arara.class.getResource(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
