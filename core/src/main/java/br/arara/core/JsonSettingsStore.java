package br.arara.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** config.json. Guarda o último valor em memória: load() é barato e pode ser chamado a cada tecla. */
final class JsonSettingsStore implements SettingsStore {
    private final Path file;
    private Settings cached;

    JsonSettingsStore(Path file) {
        this.file = file;
    }

    /** Arquivo ausente ou ilegível = padrões; campo ausente ou inválido = padrão daquele campo. */
    @Override
    public synchronized Settings load() {
        if (cached == null) cached = read();
        return cached;
    }

    private Settings read() {
        Settings d = Settings.defaults();
        if (!Files.exists(file)) return d;
        Map<?, ?> m;
        try {
            m = Json.parse(Files.readString(file)) instanceof Map<?, ?> map ? map : Map.of();
        } catch (IOException | RuntimeException e) {
            return d;
        }
        return new Settings(
                m.get("hotkey") instanceof String s ? s : d.hotkey(),
                enumOr(Settings.Model.class, m.get("model"), d.model()),
                enumOr(Settings.Accel.class, m.get("accel"), d.accel()),
                m.get("microphone") instanceof String s ? s : d.microphone(),
                m.get("startWithWindows") instanceof Boolean b ? b : d.startWithWindows(),
                m.get("showPopup") instanceof Boolean b ? b : d.showPopup(),
                m.get("escCancels") instanceof Boolean b ? b : d.escCancels());
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, Object value, E fallback) {
        try {
            return value instanceof String s ? Enum.valueOf(type, s) : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @Override
    public synchronized void save(Settings s) {
        Json.writeAtomic(file, "{\n"
                + "  \"hotkey\": " + Json.quote(s.hotkey()) + ",\n"
                + "  \"model\": " + Json.quote(s.model().name()) + ",\n"
                + "  \"accel\": " + Json.quote(s.accel().name()) + ",\n"
                + "  \"microphone\": " + Json.quote(s.microphone()) + ",\n"
                + "  \"startWithWindows\": " + s.startWithWindows() + ",\n"
                + "  \"showPopup\": " + s.showPopup() + ",\n"
                + "  \"escCancels\": " + s.escCancels() + "\n"
                + "}\n");
        cached = s;
    }
}
