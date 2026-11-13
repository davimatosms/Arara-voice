package br.arara.core;

/**
 * Configurações do usuário em %APPDATA%\Arara\config.json.
 *
 * @param hotkey      atalho no formato "Ctrl+Shift+Space" ou "Mouse4"
 * @param model       LEVE (base), EQUILIBRADO (small) ou PRECISO (large-v3-turbo)
 * @param accel       CPU ou VULKAN
 * @param microphone  nome do mixer do javax.sound, ou null para o padrão do sistema
 */
public record Settings(
        String hotkey,
        Model model,
        Accel accel,
        String microphone,
        boolean startWithWindows,
        boolean showPopup,
        boolean escCancels) {

    public enum Model { LEVE, EQUILIBRADO, PRECISO }

    public enum Accel { CPU, VULKAN }

    public static Settings defaults() {
        return new Settings("Ctrl+Shift+Space", Model.PRECISO, Accel.CPU, null, true, true, true);
    }
}
