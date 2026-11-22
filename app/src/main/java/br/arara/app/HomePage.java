package br.arara.app;

import br.arara.core.DictationEngine;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.LocalTime;

/** Início: cartão "Pronto para ditar" e o resumo da configuração. */
final class HomePage extends MainView.Page {
    private final SettingsStore settings;
    private final JLabel modelState; // o selo da barra lateral: a verdade sobre o modelo (baixando, erro, pronto)
    private final JPanel keys = Ui.row(0);
    // O atalho também chega ao app em foco (o JNativeHook não engole teclas): com Espaço, alguns apps digitam um espaço.
    private static final String SPACE_TEXT = "Se este atalho digitar um espaço em algum app, troque por outro.";
    private final JLabel spaceHint = Ui.label(SPACE_TEXT,
            Theme.regular(13), Theme.SUPPORT);
    private final JPanel grid = new JPanel(new GridLayout(2, 2, 16, 16));

    HomePage(DictationEngine engine, SettingsStore settings, Runnable openSettings, JLabel modelState) {
        this.settings = settings;
        this.modelState = modelState;

        Ui.Round box = new Ui.Round(new BorderLayout(), Theme.BG, null, 22);
        box.setPreferredSize(new Dimension(220, 210));
        box.setBorder(new EmptyBorder(8, 0, 12, 0));
        box.add(new Arara(4));
        box.add(new Waves(24, 3, 4, 14), BorderLayout.SOUTH);

        JButton test = Ui.button("Testar microfone", "mic", Ui.Kind.PRIMARY);
        test.setToolTipText("Começa a ouvir; aperte de novo para parar");
        test.addActionListener(e -> engine.toggle());
        JButton change = Ui.button("Trocar atalho", "keyboard", Ui.Kind.OUTLINE);
        change.addActionListener(e -> openSettings.run());

        JPanel text = Ui.column(
                Ui.gap(14),
                Ui.label("Pronto para ditar", Theme.bold(22), Theme.TEXT), Ui.gap(8),
                Ui.label("Aperte uma vez para começar a ouvir e de novo para colar. Esc cancela.",
                        Theme.regular(15), Theme.TEXT_SOFT),
                Ui.gap(22), Ui.caption("ATALHO"), Ui.gap(8), keys, Ui.gap(8), spaceHint, Ui.gap(16),
                Ui.row(16, test, change));

        Ui.Round hero = Ui.card(new BorderLayout(32, 0));
        hero.setBorder(new EmptyBorder(18, 18, 18, 24));
        hero.add(box, BorderLayout.WEST);
        hero.add(text);

        grid.setOpaque(false);
        // Com rolagem, como em Configurações: em janela baixa (notebook com escala de 150%) os cartões de baixo
        // ficavam cortados, sem barra para chegar a eles.
        add(Ui.scroll(Ui.column(hero, Ui.gap(32), Ui.label("Configuração", Theme.bold(20), Theme.TEXT), Ui.gap(16),
                grid)));
    }

    @Override
    String title() {
        int h = LocalTime.now().getHour();
        return h < 5 ? "Boa noite!" : h < 12 ? "Bom dia!" : h < 18 ? "Boa tarde!" : "Boa noite!";
    }

    @Override
    String subtitle() {
        return "Aperte o atalho em qualquer janela, fale e aperte de novo. O texto vai para onde está o cursor.";
    }

    @Override
    void refresh() {
        Settings s = settings.load();
        keys.removeAll();
        keys.add(Ui.keycaps(s.hotkey()));
        String solo = SettingsPage.soloWarning(s.hotkey());
        if (solo != null) spaceHint.setText(solo);
        else spaceHint.setText(SPACE_TEXT);
        spaceHint.setVisible(solo != null || s.hotkey().endsWith("+Space"));
        boolean cpu = Ui.MAC ? !br.arara.core.AraraCore.gpuOn(s) : s.accel() != Settings.Accel.VULKAN;
        grid.removeAll();
        grid.add(status("cpu", "Modelo de voz", Ui.modelName(s.model()) + " · 100% local",
                modelState.getText(), modelState.getForeground()));
        grid.add(status("mic", "Microfone",
                s.microphone() == null ? "Microfone padrão do sistema" : s.microphone(), "Pronto", Theme.READY));
        grid.add(status("settings-2", "Aceleração", cpu ? "CPU agora · Vulkan é opcional" : "Vulkan · placa de vídeo",
                cpu ? "Opcional" : "Ligado", cpu ? Theme.SUPPORT : Theme.READY));
        grid.add(status("history", "Histórico", "Salvo só neste computador", "Ligado", Theme.READY));
        revalidate();
        repaint();
    }

    private static JPanel status(String icon, String title, String detail, String badge, Color color) {
        Ui.Round circle = new Ui.Round(new BorderLayout(), Theme.BG, null, 999);
        circle.setPreferredSize(new Dimension(44, 44));
        circle.add(new JLabel(Ui.icon(icon, 22, Theme.TEXT), JLabel.CENTER));

        JLabel detailLabel = Ui.label(detail, Theme.regular(13), Theme.TEXT_SOFT);
        Ui.Round card = Ui.card(new BorderLayout(16, 0));
        card.setBorder(new EmptyBorder(18, 18, 18, 22));
        card.add(Ui.centerY(circle), BorderLayout.WEST);
        card.add(Ui.centerY(Ui.column(Ui.label(title, Theme.bold(16), Theme.TEXT), Ui.gap(2), detailLabel)));
        card.add(Ui.centerY(new Ui.Badge(badge, color)), BorderLayout.EAST);
        return card;
    }
}
