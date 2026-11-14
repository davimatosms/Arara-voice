package br.arara.app;

import br.arara.core.HistoryEntry;
import br.arara.core.HistoryStore;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Histórico: busca, lista à esquerda e detalhes do ditado à direita. */
final class HistoryPage extends MainView.Page {
    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy '·' HH:mm", PT_BR);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm");

    private final HistoryStore history;
    private final Runnable onChange;
    private final JTextField search = Ui.field("Buscar nos ditados...");
    private final DefaultListModel<HistoryEntry> model = new DefaultListModel<>();
    private final JList<HistoryEntry> list = new JList<>(model);
    private final JLabel date = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final JLabel heading = Ui.label(" ", Theme.bold(20), Theme.TEXT);
    private final JTextArea text = new JTextArea();
    private final JButton copy = Ui.button("Copiar", "copy", Ui.Kind.PRIMARY);
    private List<HistoryEntry> all = List.of();

    HistoryPage(HistoryStore history, Runnable onChange) {
        this.history = history;
        this.onChange = onChange;

        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon("search", 20, Theme.TEXT_SOFT));
        search.setPreferredSize(new Dimension(340, 42));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        JButton clear = Ui.button("Apagar tudo", "trash-2", Ui.Kind.OUTLINE);
        clear.addActionListener(e -> {
            if (confirmClear(this, history)) onChange.run();
        });
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(0, 0, 16, 0));
        top.add(search, BorderLayout.WEST);
        top.add(clear, BorderLayout.EAST);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.SURFACE);
        list.setFixedCellHeight(92);
        list.setFixedCellWidth(100); // largura fixa pequena faz a lista acompanhar a largura do cartão
        list.setCellRenderer(new Cell());
        list.getAccessibleContext().setAccessibleName("Ditados");
        list.addListSelectionListener(e -> showDetail(list.getSelectedValue()));
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(null);
        Ui.Round listCard = Ui.card(new BorderLayout());
        listCard.setBorder(new EmptyBorder(8, 8, 8, 8));
        listCard.setPreferredSize(new Dimension(340, 0));
        listCard.add(listScroll);

        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setMargin(new java.awt.Insets(0, 0, 0, 0));
        text.setFont(Theme.regular(17));
        text.setForeground(Theme.TEXT);
        text.getAccessibleContext().setAccessibleName("Texto do ditado");
        JScrollPane textScroll = new JScrollPane(text);
        textScroll.setBorder(null);
        textScroll.setOpaque(false);
        textScroll.getViewport().setOpaque(false);

        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text.getText()), null));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.column(date, Ui.gap(4), heading));
        head.add(Ui.centerY(copy), BorderLayout.EAST);
        JSeparator line = new JSeparator();
        line.setForeground(Theme.BORDER);
        JPanel north = Ui.column(head, Ui.gap(16), line, Ui.gap(20), Ui.caption("TEXTO"), Ui.gap(10));

        Ui.Round note = new Ui.Round(new BorderLayout(), Theme.BG, null, 18);
        JLabel noteLabel = Ui.label("Salvo no histórico antes de colar. Nada se perde.", Theme.regular(13), Theme.TEXT_SOFT);
        noteLabel.setIcon(Ui.icon("check", 20, Theme.GREEN));
        noteLabel.setIconTextGap(14);
        noteLabel.setBorder(new EmptyBorder(16, 16, 16, 16));
        note.add(noteLabel);

        Ui.Round detail = Ui.card(new BorderLayout(0, 12));
        detail.add(north, BorderLayout.NORTH);
        detail.add(textScroll);
        detail.add(note, BorderLayout.SOUTH);

        JPanel columns = new JPanel(new BorderLayout(16, 0));
        columns.setOpaque(false);
        columns.add(listCard, BorderLayout.WEST);
        columns.add(detail);

        add(top, BorderLayout.NORTH);
        add(columns);
    }

    @Override
    String title() {
        return "Histórico";
    }

    @Override
    String subtitle() {
        int n = all.size();
        return n + (n == 1 ? " ditado salvo" : " ditados salvos") + " neste computador.";
    }

    @Override
    void refresh() {
        all = history.all();
        filter();
    }

    private void filter() {
        String q = plain(search.getText().strip());
        model.clear();
        for (HistoryEntry e : all) if (plain(e.texto()).contains(q)) model.addElement(e);
        if (model.isEmpty()) showDetail(null);
        else list.setSelectedIndex(0);
    }

    private void showDetail(HistoryEntry e) {
        copy.setEnabled(e != null && !e.texto().isBlank());
        if (e == null) {
            date.setText(" ");
            heading.setText(all.isEmpty() ? "Nenhum ditado ainda" : "Nada encontrado");
            text.setText("");
            return;
        }
        date.setText(FULL.format(e.data()));
        int words = e.texto().isBlank() ? 0 : e.texto().strip().split("\\s+").length;
        heading.setText(words == 0 ? "Nenhuma fala detectada" : "Ditado de " + words + (words == 1 ? " palavra" : " palavras"));
        text.setText(e.texto());
        text.setCaretPosition(0);
    }

    /** Pergunta antes de apagar. Devolve true se apagou. */
    static boolean confirmClear(Component parent, HistoryStore history) {
        String[] options = {"Apagar tudo", "Cancelar"};
        int answer = JOptionPane.showOptionDialog(parent,
                "Apagar todos os ditados salvos? Não dá para desfazer.", "Apagar histórico",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) return false;
        history.clear();
        return true;
    }

    /** "Agora há pouco", "Há 12 min", "Hoje, 09:15", "Ontem, 18:02" ou a data. */
    static String when(LocalDateTime t, LocalDateTime now) {
        long min = Duration.between(t, now).toMinutes();
        if (min < 1) return "Agora há pouco";
        if (min < 60) return "Há " + min + " min";
        if (t.toLocalDate().equals(now.toLocalDate())) return "Hoje, " + HOUR.format(t);
        if (t.toLocalDate().equals(now.toLocalDate().minusDays(1))) return "Ontem, " + HOUR.format(t);
        return DATE.format(t);
    }

    /** Minúsculas e sem acento, para a busca achar "reuniao" em "reunião". */
    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(PT_BR);
    }

    /** Um item da lista: quando, prévia do texto (ou aviso de que não houve fala). */
    private static final class Cell extends Ui.Round implements ListCellRenderer<HistoryEntry> {
        private final JLabel when = Ui.label("", Theme.semibold(12), Theme.TEXT_SOFT);
        private final JLabel preview = Ui.label("", Theme.regular(14), Theme.TEXT);
        private final Ui.Badge empty = new Ui.Badge("Nenhuma fala detectada", Theme.YELLOW);

        Cell() {
            super(new BorderLayout(), Theme.SURFACE, null, 22);
            setBorder(new EmptyBorder(12, 14, 12, 14));
            add(Ui.column(when, Ui.gap(6), preview, empty), BorderLayout.NORTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HistoryEntry> l, HistoryEntry e, int index,
                                                      boolean selected, boolean focused) {
            setBackground(selected ? Theme.alpha(Theme.GREEN, 40) : Theme.SURFACE);
            when.setText(when(e.data(), LocalDateTime.now()));
            when.setForeground(selected ? Theme.GREEN : Theme.TEXT_SOFT);
            boolean blank = e.texto().isBlank();
            String t = e.texto().strip();
            if (t.length() > 72) t = t.substring(0, 70).strip() + "…";
            preview.setText("<html><div style='width:210px'>" + escape(t) + "</div></html>");
            preview.setVisible(!blank);
            empty.setVisible(blank);
            getAccessibleContext().setAccessibleName(when.getText() + ": " + (blank ? empty.getText() : t));
            return this;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
