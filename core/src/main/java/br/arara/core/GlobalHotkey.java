package br.arara.core;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
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
    private boolean modPressed; // press() já foi chamado para esse aperto
    private Runnable modTimer; // cancela a espera dos 150 ms
    private long lastKeyAt;
    private long lastModAt;
    private final LongSupplier clock;
    private final BiFunction<Runnable, Long, Runnable> scheduler; // (tarefa, atraso ms) -> cancelador
    /** Modificador solo: espera isto segurado antes de começar a ouvir, para não disparar em Ctrl+C. */
    static final long HOLD_DELAY_MS = 150;
    /** Sem soltar nem repetição por mais que isto = evento de soltar perdido. A repetição do SO é ~30 ms. */
    static final long STUCK_MS = 1500;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "arara-atalho");
        t.setDaemon(true);
        return t;
    });

    GlobalHotkey(DictationEngine engine, SettingsStore settings) {
        this(engine, settings, System::currentTimeMillis, (task, ms) -> {
            ScheduledFuture<?> f = TIMER.schedule(task, ms, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        });
    }

    GlobalHotkey(DictationEngine engine, SettingsStore settings, LongSupplier clock,
                 BiFunction<Runnable, Long, Runnable> scheduler) {
        this.engine = engine;
        this.settings = settings;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /** true: registrado no JNativeHook; false: outra fonte (o evdev no Wayland) chama os métodos native*. */
    private boolean hooked;

    /** Sem JNativeHook: quem lê o teclado ({@link LinuxEvdev}) entrega os eventos direto. */
    static GlobalHotkey unhooked(DictationEngine engine, SettingsStore settings) {
        return new GlobalHotkey(engine, settings);
    }

    static GlobalHotkey register(DictationEngine engine, SettingsStore settings) throws NativeHookException {
        // O padrão extrai a lib nativa ao lado do jar: no macOS fica dentro do Arara.app (somente leitura no .dmg e
        // quebraria a assinatura); no Linux, dentro do AppImage montado (squashfs somente leitura, UnsatisfiedLinkError).
        // macOS usa o tmp do usuário; Linux usa pasta própria 700 (nunca /tmp compartilhado: outro usuário plantaria a .so).
        // Antes de qualquer uso do GlobalScreen.
        if (System.getProperty("jnativehook.lib.path") == null) {
            if (AraraCore.MAC) System.setProperty("jnativehook.lib.path", System.getProperty("java.io.tmpdir"));
            else if (AraraCore.LINUX) System.setProperty("jnativehook.lib.path", privateNativeDir().toString());
        }
        HOOK_LOG.setLevel(Level.OFF);
        HOOK_LOG.setUseParentHandlers(false);
        GlobalHotkey h = new GlobalHotkey(engine, settings);
        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(h);
        GlobalScreen.addNativeMouseListener(h);
        h.hooked = true;
        return h;
    }

    private static java.nio.file.Path privateNativeDir() throws NativeHookException {
        java.nio.file.Path dir = AraraCore.defaultDataDir().resolve("native");
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (java.io.IOException e) {
            throw new NativeHookException("Sem pasta para a lib nativa do atalho: " + dir, e);
        }
        return dir;
    }

    void close() {
        if (!hooked) return;
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
    public synchronized void nativeKeyPressed(NativeKeyEvent e) {
        // Não dá para engolir o Esc: o app em foco também o recebe.
        if (e.getKeyCode() == NativeKeyEvent.VC_ESCAPE && settings.load().escCancels()) engine.cancel();
        Hotkey h = hotkey();
        if (h != null && h.modifierOnly()) {
            modifierPressed(e, h);
            return;
        }
        if (h == null || e.getKeyCode() != h.keyCode() || !h.modifiersMatch(e.getModifiers())) return;
        long now = clock.getAsLong();
        boolean stale = now - lastKeyAt > STUCK_MS; // soltar perdido: trata como aperto novo
        lastKeyAt = now;
        if (keyDown && !stale) return; // repetição automática de tecla segurada
        keyDown = true;
        if (settings.load().holdToTalk()) engine.press();
        else engine.toggle();
    }

    private static boolean isRight(NativeKeyEvent e, Hotkey h) {
        return e.getKeyCode() == h.keyCode()
                && (h.location() == Hotkey.ANY_SIDE || e.getKeyLocation() == h.location());
    }

    private void modifierPressed(NativeKeyEvent e, Hotkey h) {
        if (isRight(e, h)) {
            long now = clock.getAsLong();
            boolean stale = now - lastModAt > STUCK_MS;
            lastModAt = now;
            if (modDown && !stale) return; // repetição automática
            stopTimer();
            modDown = true;
            modDirty = false;
            modPressed = false;
            if (settings.load().holdToTalk()) modTimer = scheduler.apply(this::holdElapsed, HOLD_DELAY_MS);
        } else if (modDown && !modDirty) {
            modDirty = true;
            if (settings.load().holdToTalk()) {
                if (modPressed) engine.cancel(); // era um atalho de outra coisa: não cola nada
                else stopTimer();
            }
        }
    }

    private synchronized void holdElapsed() {
        if (!modDown || modDirty || modPressed) return;
        modPressed = true;
        engine.press();
    }

    private void stopTimer() {
        if (modTimer != null) modTimer.run();
        modTimer = null;
    }

    private void modifierReleased(NativeKeyEvent e, Hotkey h) {
        if (!modDown || !isRight(e, h)) return;
        modDown = false;
        stopTimer();
        if (settings.load().holdToTalk()) {
            if (!modDirty && modPressed) engine.release();
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
    public synchronized void nativeKeyReleased(NativeKeyEvent e) {
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
