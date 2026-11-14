package br.arara.app;

import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.HistoryEntry;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;

/** Pop-up de gravação: sempre por cima, sem roubar o foco, perto do pé da tela. */
final class RecordingPopup implements DictationListener {
    private static final int WIDTH = 500, HEIGHT = 150;

    private final SettingsStore settings;
    final Arara arara = new Arara(2);
    private final Waves mini = new Waves(22, 2, 4, 10);
    private final Waves waves = new Waves(27, 4, 6, 34);
    private final Ui.Badge badge = new Ui.Badge();
    private final JLabel title = Ui.label(" ", Theme.extrabold(22), Theme.TEXT);
    private final JLabel quote = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final JLabel esc = Ui.label("Esc cancela", Theme.regular(12), Theme.TEXT_SOFT);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final CardLayout middleCards = new CardLayout();
    private final JPanel middle = new JPanel(middleCards);
    private final Timer anim = new Timer(33, e -> tick());
    private final Timer hide = new Timer(1500, e -> hideNow());
    /** Conteúdo do pop-up; fica separado da janela para poder ser desenhado em teste sem tela. */
    final Ui.Round view = new Ui.Round(new BorderLayout(20, 0), Theme.SURFACE, Theme.BORDER, 32);

    private JWindow window;
    private DictationState state = DictationState.IDLE;
    private float level;
    private int frame;
    private String lastError = "Algo deu errado.";

    RecordingPopup(SettingsStore settings) {
        this.settings = settings;
        hide.setRepeats(false);

        Ui.Round box = new Ui.Round(new BorderLayout(), Theme.BG, null, 22);
        box.setPreferredSize(new Dimension(170, 122)); // 170: cabe o voo (75 px de sprite x 2) com folga
        box.setBorder(new EmptyBorder(6, 0, 10, 0));
        box.add(arara);
        box.add(mini, BorderLayout.SOUTH);

        progress.setForeground(Theme.YELLOW);
        progress.setBackground(Theme.BG);
        progress.setPreferredSize(new Dimension(270, 10));
        progress.setBorderPainted(false);
        progress.getAccessibleContext().setAccessibleName("Transcrevendo");
        middle.setOpaque(false);
        middle.add(waves, "waves");
        middle.add(Ui.centerY(progress), "progress");
        middle.add(Ui.centerY(quote), "quote");
        middle.setPreferredSize(new Dimension(270, 40));
        esc.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(Ui.column(Ui.gap(4), badge, Ui.gap(6), title, Ui.gap(2), middle), BorderLayout.NORTH);
        right.add(esc, BorderLayout.SOUTH);

        view.setBorder(new EmptyBorder(14, 14, 14, 26));
        view.add(box, BorderLayout.WEST);
        view.add(right);
        view.setSize(WIDTH, HEIGHT);
        view.setPreferredSize(new Dimension(WIDTH, HEIGHT));
    }

    @Override
    public void onState(DictationState s) {
        // Depois de "Colado!" ou erro, o pop-up some sozinho pelo timer; o IDLE logo em seguida não corta isso.
        if (s == DictationState.IDLE && (state == DictationState.PASTED || state == DictationState.ERROR)) return;
        show(s);
        if (s == DictationState.IDLE) hideNow();
        else if (settings.load().showPopup()) showWindow();
    }

    @Override
    public void onLevel(float level) {
        this.level = level;
        if (state == DictationState.LISTENING || state == DictationState.SILENCE) {
            waves.push(level);
            mini.push(level);
        }
    }

    @Override
    public void onResult(HistoryEntry entry) {
        String t = entry.texto().strip();
        quote.setText(t.isEmpty() ? "Nenhuma fala detectada." : "“" + (t.length() > 42 ? t.substring(0, 40) + "…" : t) + "”");
    }

    @Override
    public void onError(String message) {
        lastError = message;
        if (state == DictationState.ERROR) quote.setText(message);
    }

    /** Ajusta o conteúdo para o estado, sem abrir janela. */
    void show(DictationState s) {
        if (s != state) {
            frame = 0;
            arara.reset();
        }
        state = s;
        boolean recording = s == DictationState.LISTENING || s == DictationState.SILENCE;
        waves.dashed = s == DictationState.SILENCE;
        mini.dashed = s != DictationState.LISTENING;
        arara.mark = s == DictationState.SILENCE ? "?" : s == DictationState.TRANSCRIBING ? "..." : null;
        switch (s) {
            case LISTENING -> set("Ouvindo", Theme.BLUE, true, "Ouvindo", "waves");
            case SILENCE -> set("Silêncio", Theme.TEXT_SOFT, false, "Não ouvi nada", "waves");
            case TRANSCRIBING -> {
                progress.setValue(0);
                set("Transcrevendo", Theme.YELLOW, false, "Transcrevendo", "progress");
            }
            case PASTED -> set("Pronto", Theme.GREEN, false, "Colado!", "quote");
            case ERROR -> {
                quote.setText(lastError);
                set("Erro", Theme.YELLOW, false, "Não deu certo", "quote");
            }
            case IDLE -> { }
        }
        Settings cfg = settings.load();
        esc.setVisible(cfg.escCancels() && (recording || s == DictationState.TRANSCRIBING));
        view.getAccessibleContext().setAccessibleName(title.getText());
        if (s == DictationState.PASTED || s == DictationState.ERROR) {
            hide.setInitialDelay(s == DictationState.PASTED ? 1500 : 3000);
            hide.restart();
        } else {
            hide.stop();
        }
        view.repaint();
    }

    private void set(String badgeText, Color color, boolean solid, String titleText, String card) {
        badge.set(badgeText, color, solid);
        title.setText(titleText);
        middleCards.show(middle, card);
    }

    /** Arara viva: bico segue a voz, notinhas, piscar e voo no "Colado!". Só roda com o pop-up aberto. */
    void tick() {
        frame++;
        arara.animate(state, level, frame);
        // Sem progresso real do Whisper: a barra anda rápido no começo e vai freando perto do fim.
        if (state == DictationState.TRANSCRIBING) progress.setValue(progress.getValue() + (950 - progress.getValue()) / 40);
        arara.repaint();
    }

    private void showWindow() {
        if (GraphicsEnvironment.isHeadless()) return;
        if (window == null) {
            window = new JWindow();
            window.setType(Window.Type.POPUP);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setAlwaysOnTop(true);
            GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (screen.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                window.setBackground(new Color(0, 0, 0, 0));
            }
            window.getRootPane().setOpaque(false);
            window.setContentPane(view);
            window.setSize(WIDTH, HEIGHT);
        }
        Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setLocation(area.x + (area.width - WIDTH) / 2, area.y + area.height - HEIGHT - 40);
        if (!window.isVisible()) window.setVisible(true);
        anim.start();
    }

    private void hideNow() {
        anim.stop();
        hide.stop();
        state = DictationState.IDLE;
        arara.reset();
        if (window != null) window.setVisible(false);
    }
}
