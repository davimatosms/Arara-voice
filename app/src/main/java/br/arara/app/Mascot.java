package br.arara.app;

import br.arara.core.DictationState;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Estilo "Só a arara" do pop-up: a arara sem fundo, no canto da tela, para não cobrir o que a pessoa está fazendo.
 * Pousada enquanto ouve (bico e notinhas seguem a voz), "?" no silêncio, voando de um lado para o outro enquanto
 * transcreve e voando no "Colado!". Texto só numa legenda pequena, para erro e para o tempo que falta.
 */
final class Mascot extends JPanel {
    /** Menos da metade da área do pop-up completo; o que é transparente deixa o clique passar no Windows. */
    static final int WIDTH = 240, HEIGHT = 144;
    /** Quanto a arara se afasta do centro, em pixels da tela (cabe na janela com o quadro de voo de 162 px). */
    static final int DRIFT = 34;
    /** Legenda mais larga que isto quebra em linhas (mensagens de erro longas). */
    static final int CAPTION_MAX = 196;
    /** Ticks de 33 ms de uma ida e volta (~4 s): devagar, para não chamar mais atenção que o texto. */
    static final int FLIGHT_TICKS = 120;

    final Arara arara = new Arara(2);
    final Ui.Round caption = new Ui.Round(new BorderLayout(), Theme.SURFACE, Theme.BORDER, 999);
    private final JLabel captionLabel = Ui.label(" ", Theme.semibold(12), Theme.TEXT);
    private String captionText = "";

    Mascot() {
        super(new BorderLayout());
        setOpaque(false);
        caption.setBorder(new EmptyBorder(4, 12, 4, 12));
        caption.add(captionLabel);
        caption.setVisible(false);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.setOpaque(false);
        bottom.add(caption);
        add(arara);
        add(bottom, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setSize(WIDTH, HEIGHT);
    }

    /** Estado novo (ou tempo que falta novo). captionText null: sem legenda. */
    void show(DictationState s, String captionText) {
        arara.mark = s == DictationState.SILENCE ? "?" : null; // transcrevendo, o voo já diz que está trabalhando
        caption.setVisible(captionText != null);
        if (captionText != null) caption(captionText);
        getAccessibleContext().setAccessibleName(captionText != null ? captionText : label(s));
        revalidate();
        repaint();
    }

    String captionText() {
        return captionText;
    }

    /** Uma linha quando cabe; senão quebra centralizado, sem passar da janela. */
    private void caption(String text) {
        captionText = text;
        captionLabel.setText(text);
        if (captionLabel.getPreferredSize().width <= CAPTION_MAX) return;
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        captionLabel.setText("<html><div style='text-align:center;width:" + CAPTION_MAX + "px'>" + safe + "</div></html>");
    }

    /** Um passo da animação (timer de 33 ms do pop-up); tick conta desde a última troca de estado. */
    void animate(DictationState s, float level, int tick) {
        if (s == DictationState.TRANSCRIBING) {
            arara.fly(tick);
            arara.dx = (int) Math.round(DRIFT * Math.sin(2 * Math.PI * tick / FLIGHT_TICKS));
        } else {
            arara.dx = 0;
            arara.animate(s, level, tick);
        }
    }

    private static String label(DictationState s) {
        return switch (s) {
            case LISTENING -> "Ouvindo";
            case SILENCE -> "Silêncio";
            case TRANSCRIBING -> "Transcrevendo";
            case PASTED -> "Colado";
            case ERROR -> "Erro";
            case IDLE -> "Arara";
        };
    }
}
