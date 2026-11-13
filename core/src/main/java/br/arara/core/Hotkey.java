package br.arara.core;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Atalho lido de textos como "Ctrl+Shift+Space", "Alt+F9" ou "Mouse4".
 *
 * @param keyCode     código VC_* do JNativeHook, ou VC_UNDEFINED se for botão do mouse
 * @param mouseButton 3 a 5, ou 0 se for tecla
 * @param modifiers   máscaras CTRL/SHIFT/ALT/META do JNativeHook
 */
public record Hotkey(int keyCode, int mouseButton, int modifiers) {
    private static final int[] GROUPS = {
        NativeInputEvent.CTRL_MASK, NativeInputEvent.SHIFT_MASK, NativeInputEvent.ALT_MASK, NativeInputEvent.META_MASK
    };
    /** "SPACE" -> VC_SPACE, "A" -> VC_A, "F9" -> VC_F9... tirado das constantes do próprio JNativeHook. */
    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        for (Field f : NativeKeyEvent.class.getFields()) {
            if (f.getName().startsWith("VC_") && Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                try {
                    KEYS.put(f.getName().substring(3), f.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        KEYS.remove("UNDEFINED");
        for (String mod : new String[] {"CONTROL", "SHIFT", "ALT", "META"}) KEYS.remove(mod);
        KEYS.put("ESC", NativeKeyEvent.VC_ESCAPE);
    }

    /** @throws IllegalArgumentException com mensagem em PT-BR se o texto não for um atalho válido */
    public static Hotkey parse(String text) {
        int key = NativeKeyEvent.VC_UNDEFINED, button = 0, mods = 0;
        for (String part : text.split("\\+")) {
            String p = part.strip().toUpperCase(Locale.ROOT);
            switch (p) {
                case "CTRL", "CONTROL" -> mods |= NativeInputEvent.CTRL_MASK;
                case "SHIFT" -> mods |= NativeInputEvent.SHIFT_MASK;
                case "ALT" -> mods |= NativeInputEvent.ALT_MASK;
                case "WIN", "META" -> mods |= NativeInputEvent.META_MASK;
                default -> {
                    if (key != NativeKeyEvent.VC_UNDEFINED || button != 0) {
                        throw new IllegalArgumentException("Atalho com mais de uma tecla principal: " + text);
                    }
                    // Mouse1 e Mouse2 de fora: o atalho engoliria os cliques normais.
                    if (p.matches("MOUSE[3-5]")) button = p.charAt(5) - '0';
                    else if (KEYS.containsKey(p)) key = KEYS.get(p);
                    else throw new IllegalArgumentException("Tecla desconhecida no atalho: " + part.strip());
                }
            }
        }
        if (key == NativeKeyEvent.VC_UNDEFINED && button == 0) {
            throw new IllegalArgumentException("O atalho precisa de uma tecla além de Ctrl, Shift, Alt ou Win.");
        }
        return new Hotkey(key, button, mods);
    }

    /** Modificadores pressionados iguais aos do atalho (nem a mais, nem a menos). */
    boolean modifiersMatch(int nativeModifiers) {
        for (int g : GROUPS) {
            if (((nativeModifiers & g) != 0) != ((modifiers & g) != 0)) return false;
        }
        return true;
    }
}
