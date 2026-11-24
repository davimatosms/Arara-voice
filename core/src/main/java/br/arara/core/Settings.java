package br.arara.core;

/**
 * Configurações do usuário em config.json, na pasta de {@link AraraCore#defaultDataDir()}.
 *
 * @param hotkey      atalho no formato "Ctrl+Shift+Space" ou "Mouse4"
 * @param startWithWindows abrir com o sistema (no macOS, "Abrir ao iniciar sessão"); nome mantido pelo config.json
 * @param model       LEVE (base), EQUILIBRADO (small) ou PRECISO (large-v3-turbo)
 * @param accel       CPU ou VULKAN
 * @param microphone  nome do mixer do javax.sound, ou null para o padrão do sistema
 * @param checkUpdates consultar o versao.json do site ao abrir e a cada 24 h ("Verificar atualizações")
 * @param skippedVersion versão marcada com "Pular esta versão", ou null
 * @param macaw       arara do tema (Configurações › Aparência); só muda o visual
 * @param lightMode   modo claro em vez do escuro (padrão)
 * @param soundOn    tocar um som ao começar e ao parar de ouvir
 * @param sound      timbre do som
 * @param soundVolume 0 a 100
 * @param soundLength duração do som relativa à padrão
 * @param macawIcon   "Usar o ícone da arara escolhida" na bandeja, janela e Dock enquanto o app está aberto
 * @param holdToTalk  "Segurar para falar": ouve enquanto o atalho está apertado (padrão: aperta para ligar e desligar)
 * @param popupStyle  pop-up completo (padrão) ou só a arara, sem fundo, no canto da tela
 * @param pasteKeys   só Linux: colar com Ctrl+V ou com Ctrl+Shift+V (padrão lá, por causa dos terminais)
 */
public record Settings(
        String hotkey,
        Model model,
        Accel accel,
        String microphone,
        boolean startWithWindows,
        boolean showPopup,
        boolean escCancels,
        boolean checkUpdates,
        String skippedVersion,
        Macaw macaw,
        boolean lightMode,
        boolean macawIcon,
        boolean holdToTalk,
        boolean soundOn,
        Sound sound,
        int soundVolume,
        SoundLength soundLength,
        PopupStyle popupStyle,
        PasteKeys pasteKeys) {

    /** Teclas do colar no Linux: terminais só colam com Ctrl+Shift+V (lá o Ctrl+V é outra coisa). Fora do Linux não é usado. */
    public enum PasteKeys { CTRL_V, CTRL_SHIFT_V }


    public enum Model { LEVE, EQUILIBRADO, PRECISO }

    public enum Sound { ARARA, PIXEL, SUAVE }

    public enum SoundLength {
        CURTA(0.6), NORMAL(1), LONGA(1.5);
        public final double factor;

        SoundLength(double f) { factor = f; }
    }

    /** COMPLETO: cartão com texto ao vivo no pé da tela; ARARA: só a arara, sem fundo, no canto inferior direito. */
    public enum PopupStyle { COMPLETO, ARARA }

    /** No Mac: AUTO usa a GPU (Metal), CPU_ONLY não; CPU (antigo) = AUTO, menos no M1. */
    public enum Accel { CPU, VULKAN, AUTO, CPU_ONLY }

    /** Ara ararauna, Anodorhynchus hyacinthinus, Ara chloropterus, Anodorhynchus leari e Primolius maracana. */
    public enum Macaw { CANINDE, AZUL, VERMELHA, LEAR, MARACANA }

    public static Settings defaults() {
        // Igual no macOS: lá não é atalho do sistema (Spotlight é Cmd+Space, fonte de entrada é Ctrl+Space e
        // Ctrl+Option+Space, e o Cmd+Shift+Space virou a Inteligência Visual no macOS 27) e Ctrl não digita caractere.
        return new Settings("Ctrl+Shift+Space", Model.PRECISO, Accel.CPU, null, true, true, true, true, null,
                Macaw.CANINDE, false, false, false, true, Sound.ARARA, 50, SoundLength.NORMAL,
                PopupStyle.COMPLETO, AraraCore.LINUX ? PasteKeys.CTRL_SHIFT_V : PasteKeys.CTRL_V);
    }

    public Settings withHotkey(String v) {
        return new Settings(v, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withModel(Model v) {
        return new Settings(hotkey, v, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withAccel(Accel v) {
        return new Settings(hotkey, model, v, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withMicrophone(String v) {
        return new Settings(hotkey, model, accel, v, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withStartWithWindows(boolean v) {
        return new Settings(hotkey, model, accel, microphone, v, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withShowPopup(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, v, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withEscCancels(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, v, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withCheckUpdates(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, v, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withSkippedVersion(String v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, v, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withMacaw(Macaw v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, v, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withLightMode(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, v, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withMacawIcon(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, v, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withHoldToTalk(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, v, soundOn, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withSoundOn(boolean v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, v, sound, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withSound(Sound v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, v, soundVolume, soundLength, popupStyle, pasteKeys);
    }

    public Settings withSoundVolume(int v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, Math.max(0, Math.min(100, v)), soundLength, popupStyle, pasteKeys);
    }

    public Settings withPopupStyle(PopupStyle v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, v, pasteKeys);
    }

    public Settings withPasteKeys(PasteKeys v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, soundLength, popupStyle, v);
    }

    public Settings withSoundLength(SoundLength v) {
        return new Settings(hotkey, model, accel, microphone, startWithWindows, showPopup, escCancels, checkUpdates, skippedVersion, macaw, lightMode, macawIcon, holdToTalk, soundOn, sound, soundVolume, v, popupStyle, pasteKeys);
    }
}
