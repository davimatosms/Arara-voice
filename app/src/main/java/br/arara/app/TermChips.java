package br.arara.app;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fileira de termos clicáveis ("+ GitHub"): cada clique manda o termo para o dicionário. */
final class TermChips extends JPanel {
    private final Consumer<String> onPick;
    private final Map<String, JButton> chips = new LinkedHashMap<>();

    TermChips(Consumer<String> onPick) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 6));
        this.onPick = onPick;
        setOpaque(false);
        setBorder(new javax.swing.border.EmptyBorder(0, -8, 0, 0)); // o primeiro termo alinha com o título, como no Ui.row
    }

    /** Troca os termos; label é o texto do botão (ex.: "PRD · 3x"). Sem termos, a fileira some. */
    void set(Map<String, String> termToLabel) {
        removeAll();
        chips.clear();
        termToLabel.forEach((term, label) -> {
            JButton b = Ui.link(label, Theme.semibold(13), Theme.ACTIVE);
            b.setIcon(Ui.icon("plus", 14, Theme.ACTIVE));
            b.setToolTipText("Adicionar \"" + term + "\" ao dicionário");
            b.getAccessibleContext().setAccessibleName("Adicionar " + term + " ao dicionário");
            b.addActionListener(e -> onPick.accept(term));
            chips.put(term, b);
            add(b);
        });
        setVisible(!chips.isEmpty());
        revalidate();
        repaint();
    }

    List<String> terms() {
        return new ArrayList<>(chips.keySet());
    }

    String label(String term) {
        return chips.get(term).getText();
    }

    void click(String term) {
        chips.get(term).doClick();
    }
}
