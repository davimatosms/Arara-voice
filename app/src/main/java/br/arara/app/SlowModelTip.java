package br.arara.app;

import br.arara.core.DictationListener;
import br.arara.core.Settings;
import br.arara.core.Settings.Model;
import br.arara.core.SettingsStore;
import java.util.EnumSet;
import java.util.Set;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.SwingUtilities;

/** Dica gentil depois de um ditado em que o modelo ficou atrás da fala: no máximo 1x por sessão por modelo, nunca no Leve. */
final class SlowModelTip implements DictationListener {
    private final SettingsStore settings;
    private final Set<Model> shown = EnumSet.noneOf(Model.class);

    SlowModelTip(SettingsStore settings) {
        this.settings = settings;
    }

    static String name(Model m) {
        return switch (m) {
            case LEVE -> "Leve";
            case EQUILIBRADO -> "Equilibrado";
            case PRECISO -> "Preciso";
        };
    }

    /** O modelo logo abaixo, ou null no Leve. */
    static Model lighter(Model m) {
        return m.ordinal() == 0 ? null : Model.values()[m.ordinal() - 1];
    }

    static String message(Model m) {
        return "O modelo " + name(m) + " está mais lento que a sua fala neste computador. O " + name(lighter(m))
                + " deve colar quase na hora.";
    }

    /** O modelo a sugerir agora (marca como mostrado), ou null se não deve aparecer. */
    synchronized Model offer() {
        Model m = settings.load().model();
        return lighter(m) == null || !shown.add(m) ? null : m;
    }

    /** Cartão da dica; os botões chamam {@code done} com true (trocar) ou false (manter). */
    static JPanel panel(Model m, java.util.function.Consumer<Boolean> done) {
        JButton swap = Ui.button("Trocar para " + name(lighter(m)), null, Ui.Kind.PRIMARY);
        JButton keep = Ui.button("Manter", null, Ui.Kind.OUTLINE);
        swap.addActionListener(e -> done.accept(true));
        keep.addActionListener(e -> done.accept(false));
        JLabel text = Ui.label("<html><body style='width:300px'>" + message(m), Theme.regular(14), Theme.TEXT);
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(Theme.SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        p.add(text, BorderLayout.CENTER);
        p.add(Ui.row(8, swap, keep), BorderLayout.SOUTH);
        return p;
    }

    /** Janela sem foco (a pessoa segue digitando onde colou); some sozinha em 8 s. */
    @Override
    public void onSlowModel() {
        Model m = offer();
        if (m == null) return;
        SwingUtilities.invokeLater(() -> {
            JWindow w = new JWindow();
            w.setFocusableWindowState(false);
            w.setAutoRequestFocus(false);
            w.setAlwaysOnTop(true);
            w.setContentPane(panel(m, swap -> {
                w.dispose();
                Settings s = settings.load();
                if (swap && s.model() == m) settings.save(s.withModel(lighter(m)));
            }));
            w.pack();
            java.awt.Rectangle a = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            w.setLocation(a.x + (a.width - w.getWidth()) / 2, a.y + a.height - w.getHeight() - RecordingPopup.HEIGHT - 60);
            w.setVisible(true);
            Timer t = new Timer(8000, e -> w.dispose());
            t.setRepeats(false);
            t.start();
        });
    }
}
