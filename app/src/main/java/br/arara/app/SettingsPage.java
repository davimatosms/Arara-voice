package br.arara.app;

import br.arara.core.AraraCore;
import br.arara.core.HistoryStore;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Configurações: Geral, Atalho, Voz e captura, Histórico e privacidade. */
final class SettingsPage extends MainView.Page {
    private static final String DEFAULT_MIC = "Microfone padrão do sistema";

    private final SettingsStore settings;
    private final HistoryStore history;
    private final Runnable onChange;
    private final JPanel content = Ui.column();
    private final JPanel keys = Ui.row(0);

    SettingsPage(SettingsStore settings, HistoryStore history, Runnable onChange) {
        this.settings = settings;
        this.history = history;
        this.onChange = onChange;
        add(Ui.scroll(content));
        build();
    }

    @Override
    String title() {
        return "Configurações";
    }

    @Override
    String subtitle() {
        return "Tudo roda no seu computador. Nada sai daqui.";
    }

    /** Monta uma vez só: refazer a tela a cada mudança tiraria o foco do teclado do controle. */
    private void build() {
        Settings s = settings.load();
        keys.add(Ui.keycaps(s.hotkey()));

        content.add(section("Geral", "Como o Arara se comporta no dia a dia.",
                row("Iniciar com o Windows", "Fica na bandeja do sistema, pronto para ouvir.",
                        Ui.toggle(s.startWithWindows(), "Iniciar com o Windows", on -> update(x -> new Settings(
                                x.hotkey(), x.model(), x.accel(), x.microphone(), on, x.showPopup(), x.escCancels())))),
                row("Mostrar a arara ao ditar", "Pop-up pequeno que não rouba o foco da janela.",
                        Ui.toggle(s.showPopup(), "Mostrar a arara ao ditar", on -> update(x -> new Settings(
                                x.hotkey(), x.model(), x.accel(), x.microphone(), x.startWithWindows(), on, x.escCancels()))))));

        JButton record = Ui.button("Gravar", null, Ui.Kind.OUTLINE);
        record.getAccessibleContext().setAccessibleName("Gravar novo atalho");
        record.addActionListener(e -> recordHotkey(record));
        content.add(section("Atalho", "Modo liga e desliga: aperte para começar e de novo para terminar.",
                row("Atalho para ditar", "Teclas ou um botão do mouse. Evite só Ctrl+Alt (vira AltGr no ABNT2).",
                        Ui.row(8, keys, record)),
                row("Esc cancela", "Para de ouvir e não cola nada.",
                        Ui.toggle(s.escCancels(), "Esc cancela", on -> update(x -> new Settings(
                                x.hotkey(), x.model(), x.accel(), x.microphone(), x.startWithWindows(), x.showPopup(), on))))));

        List<String> mics = new ArrayList<>(List.of(DEFAULT_MIC));
        mics.addAll(AraraCore.microphones());
        if (s.microphone() != null && !mics.contains(s.microphone())) mics.add(s.microphone());
        JComboBox<String> mic = new JComboBox<>(mics.toArray(String[]::new));
        mic.setSelectedItem(s.microphone() == null ? DEFAULT_MIC : s.microphone());
        mic.setPreferredSize(new Dimension(280, 40));
        mic.getAccessibleContext().setAccessibleName("Microfone");
        mic.addActionListener(e -> {
            String m = DEFAULT_MIC.equals(mic.getSelectedItem()) ? null : (String) mic.getSelectedItem();
            update(x -> new Settings(x.hotkey(), x.model(), x.accel(), m, x.startWithWindows(), x.showPopup(), x.escCancels()));
        });
        Settings.Model[] models = Settings.Model.values();
        Settings.Accel[] accels = Settings.Accel.values();
        content.add(section("Voz e captura", "Microfone, modelo e velocidade.",
                row("Microfone", "16 kHz, mono.", mic),
                row("Modelo", "Mais preciso usa mais memória e demora um pouco mais.",
                        Ui.segmented(new String[] {"Leve", "Equilibrado", "Preciso"}, s.model().ordinal(), "Modelo",
                                i -> update(x -> new Settings(x.hotkey(), models[i], x.accel(), x.microphone(),
                                        x.startWithWindows(), x.showPopup(), x.escCancels())))),
                row("Aceleração", "Vulkan usa a placa de vídeo (NVIDIA, AMD ou Intel).",
                        Ui.segmented(new String[] {"CPU", "Vulkan"}, s.accel().ordinal(), "Aceleração",
                                i -> update(x -> new Settings(x.hotkey(), x.model(), accels[i], x.microphone(),
                                        x.startWithWindows(), x.showPopup(), x.escCancels()))))));

        JButton open = Ui.button("Abrir pasta", "folder", Ui.Kind.OUTLINE);
        open.addActionListener(e -> openDataDir());
        JButton clear = Ui.button("Apagar tudo", "trash-2", Ui.Kind.OUTLINE);
        clear.addActionListener(e -> {
            if (HistoryPage.confirmClear(this, history)) onChange.run();
        });
        content.add(section("Histórico e privacidade", "Seus ditados ficam só aqui, sem criptografia.",
                row("Pasta do histórico", "%APPDATA%\\Arara\\historico.jsonl", open),
                row("Apagar histórico", "Remove todos os ditados salvos. Não dá para desfazer.", clear)));
    }

    private void update(UnaryOperator<Settings> change) {
        settings.save(change.apply(settings.load()));
        onChange.run();
    }

    private void recordHotkey(JButton button) {
        if (!button.getText().equals("Gravar")) return; // já está gravando
        button.setText("Aperte as teclas…");
        KeyAdapter listener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                e.consume();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    button.transferFocus();
                    return;
                }
                String hotkey = hotkey(e.getModifiersEx(), e.getKeyCode());
                if (hotkey == null) return; // só modificador até agora
                String problem = problem(hotkey);
                if (problem != null) {
                    JOptionPane.showMessageDialog(SettingsPage.this, problem, "Atalho", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                button.removeKeyListener(this);
                button.setText("Gravar");
                keys.removeAll();
                keys.add(Ui.keycaps(hotkey));
                keys.revalidate();
                update(x -> new Settings(hotkey, x.model(), x.accel(), x.microphone(),
                        x.startWithWindows(), x.showPopup(), x.escCancels()));
            }
        };
        button.addKeyListener(listener);
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                button.removeKeyListener(listener);
                button.removeFocusListener(this);
                button.setText("Gravar");
            }
        });
        button.requestFocusInWindow();
    }

    /** "Ctrl+Shift+Space" a partir da tecla apertada; null se só um modificador foi apertado. */
    static String hotkey(int modifiersEx, int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
                 KeyEvent.VK_WINDOWS -> { return null; }
            default -> { }
        }
        StringBuilder sb = new StringBuilder();
        if ((modifiersEx & KeyEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((modifiersEx & KeyEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((modifiersEx & KeyEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if (keyCode == KeyEvent.VK_SPACE) sb.append("Space");
        else if ((keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) || (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9))
            sb.append((char) keyCode);
        else if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) sb.append('F').append(keyCode - KeyEvent.VK_F1 + 1);
        else sb.append(KeyEvent.getKeyText(keyCode));
        return sb.toString();
    }

    /** Motivo para recusar o atalho, ou null se está bom. */
    static String problem(String hotkey) {
        boolean ctrl = hotkey.contains("Ctrl+"), alt = hotkey.contains("Alt+"), shift = hotkey.contains("Shift+");
        boolean fKey = hotkey.matches(".*\\bF\\d+$");
        if (ctrl && alt && !shift) return "Evite só Ctrl+Alt: no teclado ABNT2 isso vira AltGr. Junte o Shift.";
        if (!ctrl && !alt && !shift && !fKey) return "Use pelo menos um modificador (Ctrl, Alt ou Shift).";
        return null;
    }

    private void openDataDir() {
        try {
            Path dir = Files.createDirectories(AraraCore.defaultDataDir());
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Não consegui abrir a pasta: " + AraraCore.defaultDataDir(), "Pasta do histórico",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JPanel section(String title, String subtitle, JComponent... rows) {
        Ui.Round card = Ui.card(new BorderLayout());
        card.setBorder(new EmptyBorder(20, 24, 8, 24));
        JPanel col = Ui.column(Ui.label(title, Theme.bold(18), Theme.TEXT), Ui.gap(2),
                Ui.label(subtitle, Theme.regular(13), Theme.TEXT_SOFT), Ui.gap(12));
        for (JComponent r : rows) {
            r.setAlignmentX(0f);
            col.add(r);
        }
        card.add(col);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(0, 0, 20, 0));
        wrap.add(card);
        wrap.setAlignmentX(0f);
        return wrap;
    }

    private static JPanel row(String title, String description, JComponent control) {
        JPanel r = new JPanel(new BorderLayout(16, 0));
        r.setOpaque(false);
        r.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER), new EmptyBorder(14, 0, 14, 0)));
        r.add(Ui.column(Ui.label(title, Theme.semibold(15), Theme.TEXT), Ui.gap(4),
                Ui.label(description, Theme.regular(12), Theme.TEXT_SOFT)));
        r.add(Ui.centerY(control), BorderLayout.EAST);
        return r;
    }
}
