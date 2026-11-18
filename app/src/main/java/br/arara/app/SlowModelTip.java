package br.arara.app;

import br.arara.core.DictationListener;
import br.arara.core.Settings;
import br.arara.core.Settings.Model;
import br.arara.core.SettingsStore;
import java.util.EnumSet;
import java.util.Set;
import javax.swing.JOptionPane;
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

    static JOptionPane pane(Model m) {
        return new JOptionPane("<html><body style='width:300px'>" + message(m), JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new String[] {"Trocar para " + name(lighter(m)), "Manter"}, "Manter");
    }

    @Override
    public void onSlowModel() {
        Model m = offer();
        if (m == null) return;
        SwingUtilities.invokeLater(() -> {
            JOptionPane p = pane(m);
            p.createDialog(null, "Arara").setVisible(true);
            if (p.getValue() instanceof String v && v.startsWith("Trocar")) {
                Settings s = settings.load();
                if (s.model() == m) settings.save(s.withModel(lighter(m)));
            }
        });
    }
}
