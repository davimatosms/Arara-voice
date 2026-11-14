package br.arara.app;

import br.arara.core.DictationEngine;
import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.DictionaryStore;
import br.arara.core.HistoryEntry;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/** Conteúdo da janela principal: barra lateral, cabeçalho e as 4 telas. */
final class MainView extends JPanel implements DictationListener {

    /** Uma tela do menu. O cabeçalho (título, subtítulo, selo) fica no MainView. */
    abstract static class Page extends JPanel {
        Page() {
            super(new BorderLayout());
            setOpaque(false);
        }

        abstract String title();

        abstract String subtitle();

        /** Relê os dados do backend. Chamado ao abrir a tela e quando algo muda. */
        void refresh() {}
    }

    private static final String[][] MENU = {
            {"Início", "house"}, {"Histórico", "history"}, {"Dicionário", "book-open"}, {"Configurações", "settings-2"}};

    private final SettingsStore settings;
    private final Page[] pages;
    private final JToggleButton[] nav = new JToggleButton[MENU.length];
    private final JLabel title = Ui.label("", Theme.extrabold(30), Theme.TEXT);
    private final JLabel subtitle = Ui.label("", Theme.regular(15), Theme.TEXT_SOFT);
    private final Ui.Badge status = new Ui.Badge("Pronto", Theme.GREEN);
    private final JLabel modelName = Ui.label("", Theme.semibold(15), Theme.TEXT);
    private final JLabel modelInfo = Ui.label("", Theme.regular(12), Theme.TEXT_SOFT);
    private final Ui.Badge modelBadge = new Ui.Badge("Pronto", Theme.GREEN);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private Page current;
    private DictationState last = DictationState.IDLE;

    MainView(DictationEngine engine, HistoryStore history, DictionaryStore dictionary, SettingsStore settings) {
        super(new BorderLayout());
        this.settings = settings;
        setBackground(Theme.BG);
        pages = new Page[] {
                new HomePage(engine, settings, () -> show(3)),
                new HistoryPage(history, this::refresh),
                new DictionaryPage(dictionary, this::refresh),
                new SettingsPage(settings, history, this::refresh)};

        add(sidebar(), BorderLayout.WEST);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.column(title, Ui.gap(4), subtitle), BorderLayout.CENTER);
        JPanel badgeBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        badgeBox.setOpaque(false);
        badgeBox.add(status);
        header.add(badgeBox, BorderLayout.EAST);
        header.setBorder(new EmptyBorder(0, 0, 24, 0));

        body.setOpaque(false);
        for (int i = 0; i < pages.length; i++) body.add(pages[i], String.valueOf(i));

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(28, 40, 40, 40));
        content.add(header, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        show(0);
    }

    void show(int index) {
        current = pages[index];
        nav[index].setSelected(true);
        cards.show(body, String.valueOf(index));
        refresh();
    }

    void refresh() {
        current.refresh();
        title.setText(current.title());
        subtitle.setText(current.subtitle());
        Settings s = settings.load();
        modelName.setText(Ui.modelName(s.model()));
        modelInfo.setText((s.accel() == Settings.Accel.CPU ? "CPU" : "Vulkan") + " · 100% local");
    }

    /** Estado do modelo de voz no cartão da barra lateral ("Baixando 42%", "Pronto"...). */
    void modelStatus(String text, java.awt.Color color) {
        modelBadge.set(text, color, false);
    }

    private JPanel sidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.SURFACE);
        side.setPreferredSize(new Dimension(232, 0));
        side.setBorder(new MatteBorder(0, 0, 0, 1, Theme.BORDER));

        JPanel logo = Ui.row(10, new Arara(1), Ui.column(
                Ui.label("Arara", Theme.extrabold(22), Theme.TEXT),
                Ui.label("ditado em PT-BR", Theme.regular(12), Theme.TEXT_SOFT)));
        logo.setBorder(new EmptyBorder(0, 0, 28, 0));

        JPanel menu = Ui.column(logo);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < MENU.length; i++) {
            JToggleButton b = new JToggleButton(MENU[i][0]);
            b.setIcon(Ui.icon(MENU[i][1], 22, () -> b.isSelected() ? Theme.GREEN : Theme.TEXT));
            b.setIconTextGap(12);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setFont(Theme.semibold(15));
            b.setBackground(Theme.SURFACE);
            b.setMargin(new Insets(7, 16, 7, 16));
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            b.putClientProperty(FlatClientProperties.STYLE, String.format(
                    "arc:20; borderWidth:1; focusWidth:0; innerFocusWidth:2; borderColor:#00000000;"
                            + " selectedBackground:%s; selectedForeground:%s; hoverBackground:%s; focusedBorderColor:%s",
                    Theme.hex(Theme.alpha(Theme.GREEN, 40)), Theme.hex(Theme.GREEN),
                    Theme.hex(Theme.alpha(Theme.GREEN, 18)), Theme.hex(Theme.TEXT)));
            int index = i;
            b.addActionListener(e -> show(index));
            group.add(b);
            nav[i] = b;
            menu.add(b);
            menu.add(Ui.gap(4));
        }

        Ui.Round model = new Ui.Round(new BorderLayout(10, 0), Theme.BG, Theme.BORDER, 20);
        model.setBorder(new EmptyBorder(12, 12, 12, 12));
        JLabel cpu = new JLabel(Ui.icon("cpu", 24, Theme.TEXT));
        cpu.setVerticalAlignment(SwingConstants.TOP);
        model.add(cpu, BorderLayout.WEST);
        model.add(Ui.column(modelName, modelInfo, Ui.gap(6), modelBadge));

        side.add(menu, BorderLayout.NORTH);
        side.add(model, BorderLayout.SOUTH);
        side.setBorder(new javax.swing.border.CompoundBorder(side.getBorder(), new EmptyBorder(24, 12, 14, 12)));
        return side;
    }

    @Override
    public void onState(DictationState state) {
        switch (state) {
            case LISTENING -> status.set("Ouvindo", Theme.BLUE, true);
            case SILENCE -> status.set("Silêncio", Theme.TEXT_SOFT, false);
            case TRANSCRIBING -> status.set("Transcrevendo", Theme.YELLOW, false);
            case PASTED -> {
                status.set("Colado", Theme.GREEN, false);
                status.setIcon(Ui.icon("check", 12, Theme.GREEN));
            }
            case ERROR -> status.set("Erro", Theme.YELLOW, false);
            // Depois de colar ou falhar, o selo continua mostrando a última ação; depois de cancelar, volta a "Pronto".
            case IDLE -> {
                if (last != DictationState.PASTED && last != DictationState.ERROR) status.set("Pronto", Theme.GREEN, false);
            }
        }
        last = state;
    }

    @Override
    public void onResult(HistoryEntry entry) {
        refresh();
    }
}
