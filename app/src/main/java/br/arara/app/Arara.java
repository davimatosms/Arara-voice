package br.arara.app;

import br.arara.core.DictationState;
import br.arara.core.Settings.Macaw;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A arara pixel art (31x42), ampliada só em escala inteira e sem suavizar. */
final class Arara extends JComponent {
    /** Pousada de bico fechado da canindé: ícone fixo do app (janela, bandeja) com a chave do ícone desligada. */
    static final BufferedImage CANINDE = read("araras/caninde/pousada.png");
    // Sprites da arara do tema (Theme.use troca); só a EDT mexe neles.
    /** Pousada de bico fechado (é também o {@code fala_0}). */
    static BufferedImage IMAGE;
    /** Bico fechado, entreaberto e aberto. */
    static BufferedImage[] FALA;
    static BufferedImage PISCAR;
    /** Ciclo de bater de asas, 81x58, sem galho. */
    static BufferedImage[] VOO;
    static {
        species(Macaw.CANINDE);
    }
    private static final Color[] NOTE_COLORS = {Theme.PRIMARY, Theme.PRIMARY_HOVER, Theme.SUPPORT};
    /** Vida de uma notinha em ticks de 33 ms (~0,6 s). */
    static final int NOTE_LIFE = 18;
    /** Ticks por quadro de voo (~100 ms) e ciclos no "Colado!". */
    static final int FLY_TICKS = 3, FLY_CYCLES = 2;

    private final int scale;
    /** Deslocamento vertical em pixels do sprite (negativo = pulinho), para manter a grade. */
    int hop;
    /** Deslocamento horizontal em pixels da tela (o mascote voa de um lado para o outro); 0 = centralizada. */
    int dx;
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

    /** Voo sem parar (mascote transcrevendo): bate as asas e sobe e desce 1 px do sprite a cada batida. */
    void fly(int tick) {
        notes.clear();
        sprite = VOO[(tick / FLY_TICKS) % VOO.length];
        hop = (tick / (FLY_TICKS * VOO.length / 2)) % 2 == 0 ? -1 : 0;
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
        dx = 0;
        notes.clear();
    }

    /** Carrega os sprites da espécie (pasta {@code araras/<id>}, gerada por arte/gerar_especies.py). */
    static void species(Macaw m) {
        String dir = "araras/" + Theme.id(m) + "/";
        IMAGE = m == Macaw.CANINDE ? CANINDE : read(dir + "pousada.png");
        FALA = new BufferedImage[] {IMAGE, read(dir + "fala_1.png"), read(dir + "fala_2.png")};
        PISCAR = read(dir + "piscar.png");
        VOO = new BufferedImage[6];
        for (int i = 0; i < VOO.length; i++) VOO[i] = read(dir + "voo_" + i + ".png");
    }

    /**
     * Ícones da janela e da barra de tarefas: a canindé de sempre, ou os da arara do tema (16, 32, 48 e 256) com a chave
     * "Usar o ícone da arara escolhida" ligada.
     */
    static List<Image> windowIcons(Macaw m, boolean macawIcon) {
        if (!macawIcon) return List.of(CANINDE);
        String dir = "araras/" + Theme.id(m) + "/icone-";
        return List.of(read(dir + "16.png"), read(dir + "32.png"), read(dir + "48.png"), read(dir + "256.png"));
    }

    /** Ícone da bandeja (e da barra de menus do Mac, colorido): a canindé de sempre, ou a cabeça da arara do tema. */
    static Image trayIcon(Macaw m, boolean macawIcon) {
        if (!macawIcon) return CANINDE;
        String dir = "araras/" + Theme.id(m) + "/bandeja-";
        return new BaseMultiResolutionImage(read(dir + "16.png"), read(dir + "32.png"));
    }

    /** Ícone do Dock no Mac (256 px). */
    static BufferedImage dockIcon(Macaw m) {
        return read("araras/" + Theme.id(m) + "/icone-256.png");
    }

    /** O quadro é da espécie atual? (Depois de trocar a arara, um quadro antigo vira a pousada nova.) */
    private static boolean current(BufferedImage img) {
        if (img == IMAGE || img == PISCAR) return true;
        for (BufferedImage f : FALA) if (f == img) return true;
        for (BufferedImage f : VOO) if (f == img) return true;
        return false;
    }

    private static int nextBlink() {
        return ThreadLocalRandom.current().nextInt(90, 151); // 3-5 s
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(alpha));
        if (!current(sprite)) sprite = IMAGE;
        int w = IMAGE.getWidth() * scale, h = IMAGE.getHeight() * scale;
        int x = (getWidth() - w) / 2 + dx, y = (getHeight() - h) / 2 + hop * scale;
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
        g2.setColor(Theme.SUPPORT);
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
