package br.arara.core;

/**
 * Configurações do usuário em config.json, na pasta de {@link AraraCore#defaultDataDir()}.
 *
 * @param hotkey      atalho no formato "Ctrl+Shift+Space" ou "Mouse4"
 * @param startWithWindows abrir com o sistema (no macOS, "Abrir ao iniciar sessão"); nome mantido pelo config.json
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
        // Igual no macOS: lá não é atalho do sistema (Spotlight é Cmd+Space, fonte de entrada é Ctrl+Space e
        // Ctrl+Option+Space, e o Cmd+Shift+Space virou a Inteligência Visual no macOS 27) e Ctrl não digita caractere.
        return new Settings("Ctrl+Shift+Space", Model.PRECISO, Accel.CPU, null, true, true, true);
    }
}
