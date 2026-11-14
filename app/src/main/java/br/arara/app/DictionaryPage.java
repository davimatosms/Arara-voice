package br.arara.app;

import br.arara.core.DictionaryStore;
import br.arara.core.Term;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;

/** Dicionário: "quando ouvir X, escrever Y". */
final class DictionaryPage extends MainView.Page {
    private final DictionaryStore dictionary;
    private final Runnable onChange;
    private final JTextField hear = Ui.field("ex.: uísper");
    private final JTextField write = Ui.field("ex.: Whisper");
    private final JLabel count = Ui.label("", Theme.semibold(12), Theme.TEXT_SOFT);
    private final JPanel rows = Ui.column();

    DictionaryPage(DictionaryStore dictionary, Runnable onChange) {
        this.dictionary = dictionary;
        this.onChange = onChange;
        hear.getAccessibleContext().setAccessibleName("Quando ouvir");
        write.getAccessibleContext().setAccessibleName("Escrever");
        JButton add = Ui.button("Adicionar", "plus", Ui.Kind.PRIMARY);
        add.addActionListener(e -> add());
        hear.addActionListener(e -> add());
        write.addActionListener(e -> add());

        Ui.Round form = Ui.card(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 18, 30));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(Ui.caption("QUANDO OUVIR"), c);
        c.gridx = 2;
        form.add(Ui.caption("ESCREVER"), c);
        c.gridy = 1;
        c.insets = new Insets(0, 0, 0, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        form.add(hear, c);
        c.gridx = 2;
        form.add(write, c);
        c.weightx = 0;
        c.gridx = 1;
        form.add(new JLabel(Ui.icon("arrow-right", 20, Theme.TEXT_SOFT)), c);
        c.gridx = 3;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 74, 0, 0);
        form.add(add, c);
        // Mesmo jeito do design: as duas colunas de texto terminam bem antes do botão.
        hear.setPreferredSize(new Dimension(250, 40));
        write.setPreferredSize(new Dimension(250, 40));

        Ui.Round table = Ui.card(new BorderLayout());
        table.setBorder(new EmptyBorder(16, 16, 12, 16));
        table.add(line(Ui.caption("QUANDO OUVIR"), Ui.caption("ESCREVER"), count, false), BorderLayout.NORTH);
        table.add(Ui.scroll(rows));

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setOpaque(false);
        content.add(form, BorderLayout.NORTH);
        content.add(table);
        add(content);
    }

    @Override
    String title() {
        return "Dicionário";
    }

    @Override
    String subtitle() {
        return "Ensine o Arara a escrever nomes e termos do seu jeito.";
    }

    @Override
    void refresh() {
        List<Term> terms = dictionary.all();
        count.setText(terms.size() + (terms.size() == 1 ? " termo" : " termos"));
        rows.removeAll();
        for (Term t : terms) {
            JButton remove = Ui.iconButton("trash-2", "Remover " + t.ouvir());
            remove.addActionListener(e -> {
                dictionary.remove(t);
                onChange.run();
            });
            rows.add(line(Ui.label(t.ouvir(), Theme.regular(15), Theme.TEXT_SOFT),
                    Ui.label(t.escrever(), Theme.bold(16), Theme.TEXT), remove, true));
        }
        if (terms.isEmpty()) {
            JLabel none = Ui.label("Nenhum termo ainda. Adicione o primeiro aqui em cima.", Theme.regular(14), Theme.TEXT_SOFT);
            none.setBorder(new EmptyBorder(24, 8, 24, 8));
            rows.add(none);
        }
        rows.revalidate();
        rows.repaint();
    }

    private void add() {
        String a = hear.getText().strip(), b = write.getText().strip();
        if (a.isEmpty() || b.isEmpty()) {
            (a.isEmpty() ? hear : write).requestFocusInWindow();
            return;
        }
        dictionary.add(new Term(a, b));
        hear.setText("");
        write.setText("");
        hear.requestFocusInWindow();
        onChange.run();
    }

    /** Linha da tabela: duas metades iguais e uma coluna fixa à direita; termos levam a seta e a divisória. */
    private static JPanel line(JComponent left, JComponent right, JComponent end, boolean term) {
        JPanel leftHalf = new JPanel(new BorderLayout());
        leftHalf.setOpaque(false);
        leftHalf.add(left);
        if (term) {
            JLabel arrow = new JLabel(Ui.icon("arrow-right", 20, Theme.BORDER));
            arrow.setBorder(new EmptyBorder(0, 0, 0, 28));
            leftHalf.add(arrow, BorderLayout.EAST);
        }
        JPanel halves = new JPanel(new GridLayout(1, 2));
        halves.setOpaque(false);
        halves.add(leftHalf);
        halves.add(right);
        JPanel endBox = Ui.centerY(end);
        endBox.setPreferredSize(new Dimension(70, 0));
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(halves);
        row.add(endBox, BorderLayout.EAST);
        row.setBorder(term ? new javax.swing.border.CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER),
                new EmptyBorder(14, 8, 14, 8)) : new EmptyBorder(4, 8, 14, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, term ? 70 : 40));
        return row;
    }
}
