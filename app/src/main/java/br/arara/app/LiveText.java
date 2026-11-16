package br.arara.app;

import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Texto ao vivo do pop-up (design 12, variação A): as 2 últimas linhas do que já foi transcrito, quebradas por
 * palavra. Palavra nova entra com fade e desliza; quando falta espaço o texto sobe uma linha, no máximo 1 vez a cada
 * 400 ms. Depois do texto confirmado vem a prévia provisória (tom mais fraco), que fica branca num crossfade de 150 ms
 * quando o trecho confirma. O tempo vem de fora ({@link #tick}) para o teste e as screenshots controlarem os quadros.
 */
final class LiveText extends JComponent {
    static final int LINE = 22, WIDTH = 270, HEIGHT = 2 * LINE + 2, WRAP = WIDTH - 4;
    static final int FADE_MS = 120, SLIDE = 4, SCROLL_MS = 180, SCROLL_EVERY_MS = 400, WORD_MS = 80, FINAL_MS = 150;
    private static final int FADE_TOP = 9, BLINK_MS = 500;
    private static final long LONG_AGO = Long.MIN_VALUE / 4;

    private final FontMetrics fm = getFontMetrics(Theme.semibold(16));
    private List<String> words = List.of();
    private List<Integer> starts = List.of();
    /** Quando cada palavra já mostrada apareceu; o tamanho é quantas palavras estão na tela. */
    private final List<Long> born = new ArrayList<>();
    private int top;
    private long scrolledAt = LONG_AGO, nextWord = LONG_AGO, now;
    private List<String> before = List.of();
    private int beforeTop;
    private long finalAt = LONG_AGO;
    /** Palavras confirmadas (o resto é prévia); oldFirm..firm estão no crossfade do tom provisório para o branco. */
    private int firm, oldFirm, beforeFirm;
    private long firmAt = LONG_AGO;
    String placeholder;
    boolean caret, reduceMotion;

    LiveText() {
        setOpaque(false);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
    }

    boolean hasText() { return !words.isEmpty(); }
    int top() { return top; }
    int shown() { return born.size(); }

    void clear() {
        words = List.of();
        starts = List.of();
        born.clear();
        top = 0;
        before = List.of();
        finalAt = LONG_AGO;
        firm = oldFirm = 0;
        repaint();
    }

    /**
     * Texto ao vivo até agora: o confirmado e, depois, a prévia provisória (tom mais fraco); substitui o anterior. As
     * palavras novas entram uma a uma no {@link #tick}; palavra da prévia que confirma fica branca em 150 ms.
     */
    void setLive(String confirmed, String provisional, long now) {
        int count = confirmed.isBlank() ? 0 : confirmed.strip().split("\\s+").length;
        if (count != firm) {
            oldFirm = Math.min(firm, count);
            firm = count;
            firmAt = now;
        }
        update(confirmed + " " + provisional, false, now);
    }

    /** Parcial: o mesmo que {@link #setLive} sem prévia. Final: troca tudo de uma vez (já com o dicionário) num crossfade de 150 ms. */
    void setText(String text, boolean fin, long now) {
        if (!fin) {
            setLive(text, "", now);
            return;
        }
        beforeFirm = firm;
        firm = oldFirm = text.isBlank() ? 0 : text.strip().split("\\s+").length;
        update(text, true, now);
    }

    private void update(String text, boolean fin, long now) {
        this.now = now;
        List<String> next = text.isBlank() ? List.of() : Arrays.asList(text.strip().split("\\s+"));
        List<Integer> nextStarts = wrap(next, fm::stringWidth, WRAP);
        int lastTop = Math.max(0, nextStarts.size() - 2);
        if (fin || reduceMotion) {
            if (fin && !reduceMotion && !next.equals(words.subList(0, born.size()))) {
                before = words.subList(0, born.size());
                beforeTop = top;
                finalAt = now;
            }
            words = next;
            starts = nextStarts;
            born.clear();
            for (int i = 0; i < next.size(); i++) born.add(LONG_AGO);
            top = lastTop;
        } else {
            int same = 0;
            while (same < born.size() && same < next.size() && next.get(same).equals(words.get(same))) same++;
            born.subList(same, born.size()).clear();
            words = next;
            starts = nextStarts;
            // Até 4 linhas novas entram animadas (~2 s, bem antes do próximo trecho); se vier mais, o começo aparece já.
            int animateFrom = nextStarts.isEmpty() ? 0 : nextStarts.get(Math.max(0, lastTop - 2));
            if (born.size() < animateFrom) {
                while (born.size() < animateFrom) born.add(LONG_AGO);
                top = Math.max(top, lastTop - 3);
            }
            top = Math.min(top, lastTop);
        }
        repaint();
    }

    /** Um quadro (timer de 33 ms do pop-up): revela a próxima palavra e, se ela não couber, sobe uma linha. */
    void tick(long now) {
        this.now = now;
        if (born.size() < words.size() && now >= nextWord) {
            boolean fits = lineOf(born.size()) <= top + 1;
            if (!fits && now - scrolledAt >= SCROLL_EVERY_MS) {
                top++;
                scrolledAt = now;
                fits = true;
            }
            if (fits) {
                born.add(now);
                nextWord = now + WORD_MS;
            }
        }
        repaint();
    }

    /** Começo de cada linha (índice da palavra), quebrando por palavra na largura max. */
    static List<Integer> wrap(List<String> words, ToIntFunction<String> width, int max) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            if (starts.isEmpty() || width.applyAsInt(String.join(" ", words.subList(starts.get(starts.size() - 1), i + 1))) > max) {
                starts.add(i);
            }
        }
        return starts;
    }

    private int lineOf(int word) {
        return lineOfIn(starts, word);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(Theme.semibold(16));
        g2.clipRect(0, 0, getWidth(), getHeight());
        float fade = reduceMotion ? 1 : clamp((now - finalAt) / (float) FINAL_MS);
        if (born.isEmpty() && placeholder != null) {
            g2.setColor(Theme.TEXT_SOFT);
            g2.drawString(placeholder, 0, baseline(1));
            drawCaret(g2, fm.stringWidth(placeholder), 1);
        } else {
            if (fade < 1) {
                List<Integer> beforeStarts = wrap(before, fm::stringWidth, WRAP);
                drawLines(g2, before, beforeStarts, before.size(), beforeTop, 1, 1 - fade, false, beforeFirm);
            }
            float scroll = reduceMotion ? 1 : clamp((now - scrolledAt) / (float) SCROLL_MS);
            drawLines(g2, words, starts, born.size(), top, 1 - (float) Math.pow(1 - scroll, 3), fade, caret, firm);
        }
        // A linha que sai pelo topo some num degradê da cor do pop-up.
        g2.setPaint(new GradientPaint(0, 0, Theme.SURFACE, 0, FADE_TOP, Theme.alpha(Theme.SURFACE, 0)));
        g2.fillRect(0, 0, getWidth(), FADE_TOP);
        g2.dispose();
    }

    /** scroll: 0 no começo da subida (linha de cima ainda no lugar) até 1 (assentou). */
    private void drawLines(Graphics2D g2, List<String> words, List<Integer> starts, int shown, int top,
                           float scroll, float alpha, boolean withCaret, int firm) {
        boolean current = words == this.words;
        float confirming = reduceMotion || !current ? 1 : clamp((now - firmAt) / (float) FINAL_MS);
        if (shown == 0) return;
        int last = lineOfIn(starts, shown - 1);
        float offset = (1 - scroll) * LINE;
        for (int line = Math.max(0, top - 1); line <= Math.min(last, top + 1); line++) {
            int y = Math.round(1 + (line - top) * LINE + offset);
            float lineAlpha = line < top ? 1 - scroll : 1;
            int from = starts.get(line), to = line + 1 < starts.size() ? Math.min(starts.get(line + 1), shown) : shown;
            for (int i = from; i < to; i++) {
                float a = reduceMotion || words != this.words ? 1 : clamp((now - born.get(i)) / (float) FADE_MS);
                int x = from == i ? 0 : fm.stringWidth(String.join(" ", words.subList(from, i)) + " ");
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha * lineAlpha * a));
                g2.setColor(i >= firm ? Theme.TEXT_SOFT : current && i >= oldFirm ? mix(Theme.TEXT_SOFT, Theme.TEXT, confirming) : Theme.TEXT);
                g2.drawString(words.get(i), x + Math.round((1 - a) * SLIDE), baseline(y));
            }
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
            if (withCaret && line == last) drawCaret(g2, fm.stringWidth(String.join(" ", words.subList(from, to))), y);
        }
        g2.setComposite(AlphaComposite.SrcOver);
    }

    private static int lineOfIn(List<Integer> starts, int word) {
        int line = starts.size() - 1;
        while (line > 0 && starts.get(line) > word) line--;
        return line;
    }

    private void drawCaret(Graphics2D g2, int x, int y) {
        if (!caret || (!reduceMotion && Math.floorMod(now, 2L * BLINK_MS) >= BLINK_MS)) return;
        g2.setColor(Theme.ACCENT);
        g2.fillRoundRect(x + 4, y + (LINE - 17) / 2, 2, 17, 2, 2);
    }

    /** 2 px acima do centro da linha: a Nunito tem ascendente alto, e assim bate com o FigCraft. */
    private int baseline(int y) {
        return y + (LINE - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent() - 2;
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
