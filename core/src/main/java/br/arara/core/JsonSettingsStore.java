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
                m.get("escCancels") instanceof Boolean b ? b : d.escCancels(),
                m.get("checkUpdates") instanceof Boolean b ? b : d.checkUpdates(), // ausente (config antigo) = ligado
                m.get("skippedVersion") instanceof String s ? s : d.skippedVersion(),
                // ausentes (config de antes dos temas) = canindé, escuro, ícone fixo
                enumOr(Settings.Macaw.class, m.get("macaw"), d.macaw()),
                m.get("lightMode") instanceof Boolean b ? b : d.lightMode(),
                m.get("macawIcon") instanceof Boolean b ? b : d.macawIcon(),
                m.get("holdToTalk") instanceof Boolean b ? b : d.holdToTalk(), // ausente = apertar para ligar/desligar
                // ausentes (config de antes do som) = som ligado, arara, 50%, duração normal
                m.get("soundOn") instanceof Boolean b ? b : d.soundOn(),
                enumOr(Settings.Sound.class, m.get("sound"), d.sound()),
                m.get("soundVolume") instanceof Number n ? Math.max(0, Math.min(100, n.intValue())) : d.soundVolume(),
                enumOr(Settings.SoundLength.class, m.get("soundLength"), d.soundLength()),
                enumOr(Settings.PopupStyle.class, m.get("popupStyle"), d.popupStyle())); // ausente = completo
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
                + "  \"escCancels\": " + s.escCancels() + ",\n"
                + "  \"checkUpdates\": " + s.checkUpdates() + ",\n"
                + "  \"skippedVersion\": " + Json.quote(s.skippedVersion()) + ",\n"
                + "  \"macaw\": " + Json.quote(s.macaw().name()) + ",\n"
                + "  \"lightMode\": " + s.lightMode() + ",\n"
                + "  \"macawIcon\": " + s.macawIcon() + ",\n"
                + "  \"holdToTalk\": " + s.holdToTalk() + ",\n"
                + "  \"soundOn\": " + s.soundOn() + ",\n"
                + "  \"sound\": " + Json.quote(s.sound().name()) + ",\n"
                + "  \"soundVolume\": " + s.soundVolume() + ",\n"
                + "  \"soundLength\": " + Json.quote(s.soundLength().name()) + ",\n"
                + "  \"popupStyle\": " + Json.quote(s.popupStyle().name()) + "\n"
                + "}\n");
        cached = s;
    }
}
