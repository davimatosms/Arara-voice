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

    private GlobalHotkey(DictationEngine engine, SettingsStore settings) {
        this.engine = engine;
        this.settings = settings;
    }

    static GlobalHotkey register(DictationEngine engine, SettingsStore settings) throws NativeHookException {
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
        if (h == null || e.getKeyCode() != h.keyCode() || !h.modifiersMatch(e.getModifiers())) return;
        if (keyDown) return; // repetição automática de tecla segurada
        keyDown = true;
        engine.toggle();
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        Hotkey h = hotkey();
        if (h != null && e.getKeyCode() == h.keyCode()) keyDown = false;
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        Hotkey h = hotkey();
        if (h != null && h.mouseButton() == e.getButton() && h.modifiersMatch(e.getModifiers())) engine.toggle();
    }
}
