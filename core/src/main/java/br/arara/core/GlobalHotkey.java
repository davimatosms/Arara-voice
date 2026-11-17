package br.arara.core;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Escuta teclado e mouse fora da janela. Relê o atalho das configurações a cada evento, então trocar vale na hora. */
final class GlobalHotkey implements NativeKeyListener, NativeMouseListener {
    // Referência forte: se o logger for coletado pelo GC, o nível volta ao padrão e o JNativeHook volta a falar.
    private static final Logger HOOK_LOG = Logger.getLogger(GlobalScreen.class.getPackage().getName());

    private final DictationEngine engine;
    private final SettingsStore settings;
    private String parsedFrom;
    private Hotkey hotkey;
    private boolean keyDown;
    private boolean mouseDown;
    private boolean modDown; // modificador direito sozinho está apertado
    private boolean modDirty; // outra tecla entrou enquanto ele estava apertado (ex.: Ctrl direito + C)

    GlobalHotkey(DictationEngine engine, SettingsStore settings) {
        this.engine = engine;
        this.settings = settings;
    }

    static GlobalHotkey register(DictationEngine engine, SettingsStore settings) throws NativeHookException {
        // macOS: o padrão extrai a .dylib ao lado do jar, dentro do Arara.app (somente leitura no .dmg e quebraria a
        // assinatura). Vai para a pasta temporária do usuário. Antes de qualquer uso do GlobalScreen.
        if (AraraCore.MAC && System.getProperty("jnativehook.lib.path") == null)
            System.setProperty("jnativehook.lib.path", System.getProperty("java.io.tmpdir"));
        HOOK_LOG.setLevel(Level.OFF);
        HOOK_LOG.setUseParentHandlers(false);
        GlobalHotkey h = new GlobalHotkey(engine, settings);
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(h);
        GlobalScreen.addNativeMouseListener(h);
        return h;
    }

    void close() {
        GlobalScreen.removeNativeKeyListener(this);
        GlobalScreen.removeNativeMouseListener(this);
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException ignored) {
            // já estava desligado
        }
    }

    /** Atalho inválido no config.json = atalho desligado (a UI valida com Hotkey.parse antes de salvar). */
    private Hotkey hotkey() {
        String text = settings.load().hotkey();
        if (!text.equals(parsedFrom)) {
            parsedFrom = text;
            try {
                hotkey = Hotkey.parse(text);
            } catch (IllegalArgumentException e) {
                hotkey = null;
            }
        }
        return hotkey;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        // Não dá para engolir o Esc: o app em foco também o recebe.
        if (e.getKeyCode() == NativeKeyEvent.VC_ESCAPE && settings.load().escCancels()) engine.cancel();
        Hotkey h = hotkey();
        if (h != null && h.modifierOnly()) {
            modifierPressed(e, h);
            return;
        }
        if (h == null || e.getKeyCode() != h.keyCode() || !h.modifiersMatch(e.getModifiers())) return;
        if (keyDown) return; // repetição automática de tecla segurada
        keyDown = true;
        if (settings.load().holdToTalk()) engine.press();
        else engine.toggle();
    }

    private static boolean isRight(NativeKeyEvent e, Hotkey h) {
        return e.getKeyCode() == h.keyCode() && e.getKeyLocation() == h.location();
    }

    private void modifierPressed(NativeKeyEvent e, Hotkey h) {
        if (isRight(e, h)) {
            if (modDown) return; // repetição automática
            modDown = true;
            modDirty = false;
            if (settings.load().holdToTalk()) engine.press();
        } else if (modDown && !modDirty) {
            modDirty = true;
            if (settings.load().holdToTalk()) engine.cancel(); // era um atalho de outra coisa: não cola nada
        }
    }

    private void modifierReleased(NativeKeyEvent e, Hotkey h) {
        if (!modDown || !isRight(e, h)) return;
        modDown = false;
        if (settings.load().holdToTalk()) {
            if (!modDirty) engine.release();
        } else if (!modDirty) engine.toggle();
    }

    /** Tecla do combo (principal ou modificador) solta: no modo segurar, encerra o ditado. */
    private void released(int keyCode, Hotkey h) {
        int mod = switch (keyCode) {
            case NativeKeyEvent.VC_CONTROL -> NativeKeyEvent.CTRL_MASK;
            case NativeKeyEvent.VC_SHIFT -> NativeKeyEvent.SHIFT_MASK;
            case NativeKeyEvent.VC_ALT -> NativeKeyEvent.ALT_MASK;
            case NativeKeyEvent.VC_META -> NativeKeyEvent.META_MASK;
            default -> 0;
        };
        if (!keyDown || (keyCode != h.keyCode() && (mod & h.modifiers()) == 0)) return;
        keyDown = false;
        if (settings.load().holdToTalk()) engine.release();
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        Hotkey h = hotkey();
        if (h == null) return;
        if (h.modifierOnly()) modifierReleased(e, h);
        else released(e.getKeyCode(), h);
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        Hotkey h = hotkey();
        if (h == null || h.mouseButton() != e.getButton() || !h.modifiersMatch(e.getModifiers())) return;
        if (settings.load().holdToTalk()) {
            mouseDown = true;
            engine.press();
        } else engine.toggle();
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {
        Hotkey h = hotkey();
        if (mouseDown && h != null && h.mouseButton() == e.getButton()) {
            mouseDown = false;
            engine.release();
        }
    }
}
