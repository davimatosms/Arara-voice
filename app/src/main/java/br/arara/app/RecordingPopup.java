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
import java.util.function.LongSupplier;

/** Pop-up de gravação: sempre por cima, sem roubar o foco, perto do pé da tela. */
final class RecordingPopup implements DictationListener {
    static final int WIDTH = 500, HEIGHT = 150;

    private final SettingsStore settings;
    final Arara arara = new Arara(2);
    private final Waves mini = new Waves(22, 2, 4, 10);
    private final Waves waves = new Waves(27, 4, 6, 24);
    final Ui.Badge badge = new Ui.Badge();
    /** Texto ao vivo no lugar do título; o título só aparece sem texto ("Não ouvi nada", "Transcrevendo", erro). */
    final LiveText live = new LiveText();
    private final JLabel title = Ui.label(" ", Theme.extrabold(22), Theme.TEXT);
    private final JLabel quote = Ui.label(" ", Theme.regular(13), Theme.TEXT_SOFT);
    private final JLabel esc = Ui.label("Esc cancela", Theme.regular(12), Theme.TEXT_SOFT);
    private final JLabel pasted = Ui.label("colado no app em foco", Theme.bold(12), Theme.READY);
    private final CardLayout textCards = new CardLayout();
    private final JPanel text = new JPanel(textCards);
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final CardLayout middleCards = new CardLayout();
    private final JPanel middle = new JPanel(middleCards);
    private final Timer anim = new Timer(33, e -> tick());
    private final Timer hide = new Timer(1500, e -> hideNow());
    /** Conteúdo do pop-up; fica separado da janela para poder ser desenhado em teste sem tela. */
    final Ui.Round view = new Ui.Round(new BorderLayout(20, 0), Theme.SURFACE, Theme.BORDER, 32);

    /** Relógio das animações em ms; o teste troca para desenhar quadros exatos. */
    LongSupplier clock = () -> System.nanoTime() / 1_000_000;
    private JWindow window;
    private DictationState state = DictationState.IDLE;
    private double pending; // segundos estimados que faltam na transcrição; 0: sem número
    private long awaitingSince; // quando começou a espera do "Aprovado" (relógio das animações)
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

        progress.setForeground(Theme.SUPPORT);
        progress.setBackground(Theme.BG);
        progress.setPreferredSize(new Dimension(270, 8));
        progress.setBorderPainted(false);
        progress.getAccessibleContext().setAccessibleName("Transcrevendo");
        middle.setOpaque(false);
        middle.add(waves, "waves");
        middle.add(Ui.centerY(progress), "progress");
        middle.add(Ui.centerY(quote), "quote");
        pasted.setIcon(Ui.icon("check", 12, Theme.READY));
        pasted.setIconTextGap(4);
        middle.add(Ui.centerY(pasted), "pasted");
        middle.setPreferredSize(new Dimension(270, 24));
        text.setOpaque(false);
        text.add(live, "live");
        text.add(title, "title");
        text.setPreferredSize(new Dimension(LiveText.WIDTH, LiveText.HEIGHT));
        live.placeholder = "Pode falar…";

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(badge, BorderLayout.WEST);
        top.add(esc, BorderLayout.EAST);
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(Ui.column(Ui.gap(4), top, Ui.gap(7), text, Ui.gap(5), middle), BorderLayout.NORTH);

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
    public void onLiveText(String confirmed, String provisional) {
        boolean had = live.hasText();
        live.setLive(confirmed, provisional, now());
        // O primeiro texto troca o título ("Não ouvi nada", "Transcrevendo") pelo texto e o selo por "Pausa".
        if (!had && state != DictationState.PASTED && state != DictationState.ERROR && state != DictationState.IDLE) show(state);
    }

    /** Estimativa do que falta depois da parada (0: sem número). */
    @Override
    public void onPending(double seconds) {
        if (state != DictationState.TRANSCRIBING || Math.round(seconds) == Math.round(pending)) return;
        pending = seconds;
        show(state);
    }

    @Override
    public void onResult(HistoryEntry entry) {
        String t = entry.texto().strip();
        if (t.isEmpty()) live.clear();
        else live.setText(t, true, now());
        quote.setText("Nenhuma fala detectada.");
    }

    @Override
    public void onError(String message) {
        lastError = message;
        if (state == DictationState.ERROR) quote.setText(message);
    }

    /** Ajusta o conteúdo para o estado, sem abrir janela. */
    void show(DictationState s) {
        boolean changed = s != state;
        if (changed) {
            pending = 0;
            frame = 0;
            arara.reset();
        }
        // Ditado novo: o texto do anterior sai.
        if (s == DictationState.LISTENING && (state == DictationState.IDLE || state == DictationState.PASTED || state == DictationState.ERROR)) {
            live.clear();
        }
        state = s;
        boolean recording = s == DictationState.LISTENING || s == DictationState.SILENCE;
        waves.dashed = s == DictationState.SILENCE;
        mini.dashed = s != DictationState.LISTENING;
        arara.mark = s == DictationState.SILENCE ? "?" : s == DictationState.TRANSCRIBING ? "..." : null;
        boolean has = live.hasText();
        live.caret = s == DictationState.LISTENING;
        switch (s) {
            case LISTENING -> set("Ouvindo", Theme.ACCENT, true, null, "waves");
            case SILENCE -> set(has ? "Pausa" : "Silêncio", Theme.TEXT_SOFT, false, has ? null : "Não ouvi nada", "waves");
            case TRANSCRIBING -> {
                if (changed) progress.setValue(0);
                String faltam = pending > 0 ? "Faltam ~" + Math.round(pending) + " s" : null;
                set(faltam != null ? faltam : "Transcrevendo", Theme.SUPPORT, false,
                        has ? null : faltam != null ? "Transcrevendo… faltam ~" + Math.round(pending) + " s" : "Transcrevendo", "progress");
            }
            case AWAITING_APPROVAL -> {
                // Ditado chamado por voz: o texto fica na tela e a barra mostra o prazo para dizer "Aprovado".
                if (changed) {
                    awaitingSince = now();
                    progress.setValue(1000);
                }
                set("Diga “Aprovado”", Theme.ACCENT, true, has ? null : "Diga “Aprovado”", "progress");
            }
            case PASTED -> {
                if (has) set("Colado!", Theme.READY, false, null, "pasted");
                else set("Pronto", Theme.READY, false, "Colado!", "quote");
            }
            case ERROR -> {
                quote.setText(lastError);
                set("Erro", Theme.SUPPORT, false, "Não deu certo", "quote");
            }
            case IDLE -> { }
        }
        Settings cfg = settings.load();
        esc.setVisible(cfg.escCancels() && (recording || s == DictationState.TRANSCRIBING || s == DictationState.AWAITING_APPROVAL));
        if (s == DictationState.PASTED || s == DictationState.ERROR) {
            hide.setInitialDelay(s == DictationState.PASTED ? 1500 : 3000);
            hide.restart();
        } else {
            hide.stop();
        }
        view.repaint();
    }

    /** titleText null: mostra o texto ao vivo no lugar do título. */
    private void set(String badgeText, Color color, boolean solid, String titleText, String card) {
        badge.set(badgeText, color, solid);
        if (titleText != null) title.setText(titleText);
        textCards.show(text, titleText == null ? "live" : "title");
        middleCards.show(middle, card);
        view.getAccessibleContext().setAccessibleName(titleText != null ? titleText : badgeText);
    }

    /** Arara viva: bico segue a voz, notinhas, piscar e voo no "Colado!". Só roda com o pop-up aberto. */
    void tick() {
        frame++;
        arara.animate(state, level, frame);
        live.tick(now());
        // Sem progresso real do Whisper: a barra anda rápido no começo e vai freando perto do fim.
        if (state == DictationState.TRANSCRIBING) progress.setValue(progress.getValue() + (950 - progress.getValue()) / 40);
        if (state == DictationState.AWAITING_APPROVAL) { // contagem regressiva do prazo da aprovação
            long left = br.arara.core.DictationEngine.APPROVAL_TIMEOUT_MS - (now() - awaitingSince);
            progress.setValue((int) Math.max(0, left * 1000 / br.arara.core.DictationEngine.APPROVAL_TIMEOUT_MS));
        }
        arara.repaint();
    }

    /** Barra do prazo da aprovação, de 1000 (cheia) a 0. */
    int countdown() {
        return progress.getValue();
    }

    private long now() {
        return clock.getAsLong();
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
