package br.arara.core;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teclado direto do kernel ({@code /dev/input/event*}), para o Wayland: lá o JNativeHook só ouve teclas com uma
 * janela XWayland em foco, e o atalho do sistema avisa o aperto mas nunca o soltar. Com isto o Arara vê apertar e
 * soltar em qualquer janela, e voltam a funcionar o "Segurar para falar" e o atalho de modificador sozinho. O botão
 * do mouse fica de fora: pediria ler também os mouses.
 *
 * <p>Só vale se o usuário puder ler os dispositivos (regra do udev com {@code uaccess} ou grupo {@code input}; veja
 * docs/linux.md). Sem isso, {@link #available()} dá false e nada muda. Só lê: não prende o dispositivo nem engole
 * teclas. Os eventos viram os mesmos {@code NativeKeyEvent} do JNativeHook e entram no {@link GlobalHotkey}.
 */
final class LinuxEvdev implements AutoCloseable {
    /** struct input_event em 64 bits: timeval (16 bytes), tipo (u16), código (u16), valor (s32). */
    static final int EVENT_SIZE = 24;
    private static final int EV_KEY = 1;
    private static final Path DEVICES = Path.of("/proc/bus/input/devices");
    /** De quanto em quanto tempo procura teclado novo (USB ou Bluetooth ligado depois). */
    private static final long RESCAN_MS = 3000;

    // Lado esquerdo/direito de cada modificador: {código do evdev, VC_*, lado, máscara}.
    private static final int[][] MODIFIERS = {
        {29, NativeKeyEvent.VC_CONTROL, NativeKeyEvent.KEY_LOCATION_LEFT, NativeInputEvent.CTRL_L_MASK},
        {97, NativeKeyEvent.VC_CONTROL, NativeKeyEvent.KEY_LOCATION_RIGHT, NativeInputEvent.CTRL_R_MASK},
        {42, NativeKeyEvent.VC_SHIFT, NativeKeyEvent.KEY_LOCATION_LEFT, NativeInputEvent.SHIFT_L_MASK},
        {54, NativeKeyEvent.VC_SHIFT, NativeKeyEvent.KEY_LOCATION_RIGHT, NativeInputEvent.SHIFT_R_MASK},
        {56, NativeKeyEvent.VC_ALT, NativeKeyEvent.KEY_LOCATION_LEFT, NativeInputEvent.ALT_L_MASK},
        {100, NativeKeyEvent.VC_ALT, NativeKeyEvent.KEY_LOCATION_RIGHT, NativeInputEvent.ALT_R_MASK},
        {125, NativeKeyEvent.VC_META, NativeKeyEvent.KEY_LOCATION_LEFT, NativeInputEvent.META_L_MASK},
        {126, NativeKeyEvent.VC_META, NativeKeyEvent.KEY_LOCATION_RIGHT, NativeInputEvent.META_R_MASK},
    };
    /** Teclas fora do bloco principal, onde o código do evdev não é o VC_* do JNativeHook. */
    private static final Map<Integer, Integer> EXTENDED = new HashMap<>();

    static {
        int[][] pairs = {
            {99, NativeKeyEvent.VC_PRINTSCREEN}, {102, NativeKeyEvent.VC_HOME}, {103, NativeKeyEvent.VC_UP},
            {104, NativeKeyEvent.VC_PAGE_UP}, {105, NativeKeyEvent.VC_LEFT}, {106, NativeKeyEvent.VC_RIGHT},
            {107, NativeKeyEvent.VC_END}, {108, NativeKeyEvent.VC_DOWN}, {109, NativeKeyEvent.VC_PAGE_DOWN},
            {110, NativeKeyEvent.VC_INSERT}, {111, NativeKeyEvent.VC_DELETE}, {119, NativeKeyEvent.VC_PAUSE},
            {183, NativeKeyEvent.VC_F13}, {184, NativeKeyEvent.VC_F14}, {185, NativeKeyEvent.VC_F15},
            {186, NativeKeyEvent.VC_F16}, {187, NativeKeyEvent.VC_F17}, {188, NativeKeyEvent.VC_F18},
            {189, NativeKeyEvent.VC_F19}, {190, NativeKeyEvent.VC_F20}, {191, NativeKeyEvent.VC_F21},
            {192, NativeKeyEvent.VC_F22}, {193, NativeKeyEvent.VC_F23}, {194, NativeKeyEvent.VC_F24},
        };
        for (int[] p : pairs) EXTENDED.put(p[0], p[1]);
    }

    private final NativeKeyListener sink; // o GlobalHotkey em produção
    private final Set<Path> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private int modifiers; // máscaras do JNativeHook dos modificadores apertados agora

    LinuxEvdev(NativeKeyListener sink) {
        this.sink = sink;
    }

    /** Entrega tudo ao atalho global, como o JNativeHook faria. */
    static LinuxEvdev start(GlobalHotkey hotkey) {
        LinuxEvdev e = new LinuxEvdev(hotkey);
        Thread t = new Thread(e::scanLoop, "arara-evdev");
        t.setDaemon(true);
        t.start();
        return e;
    }

    /** Há algum teclado que este usuário pode ler? (Linux de 64 bits; o tamanho do evento muda no de 32.) */
    static boolean available() {
        if (!AraraCore.LINUX || !"64".equals(System.getProperty("sun.arch.data.model"))) return false;
        try {
            for (Path p : devices(Files.readString(DEVICES))) if (Files.isReadable(p)) return true;
        } catch (IOException | RuntimeException e) {
            // sem /proc: não há o que ler
        }
        return false;
    }

    /** Os teclados (handler "kbd") de {@code /proc/bus/input/devices}. */
    static List<Path> devices(String proc) {
        List<Path> out = new ArrayList<>();
        for (String line : proc.split("\\R")) {
            if (!line.startsWith("H: Handlers=")) continue;
            List<String> handlers = List.of(line.substring(12).trim().split("\\s+"));
            if (!handlers.contains("kbd")) continue;
            for (String h : handlers) if (h.matches("event\\d+")) out.add(Path.of("/dev/input", h));
        }
        return out;
    }

    private void scanLoop() {
        while (!closed) {
            try {
                for (Path p : devices(Files.readString(DEVICES))) {
                    if (Files.isReadable(p) && open.add(p)) {
                        Thread t = new Thread(() -> read(p), "arara-evdev-" + p.getFileName());
                        t.setDaemon(true);
                        t.start();
                    }
                }
                Thread.sleep(RESCAN_MS);
            } catch (IOException | RuntimeException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Lê até o dispositivo sumir (desplugado) ou o Arara fechar; depois o laço de busca pode reabrir. */
    private void read(Path device) {
        byte[] raw = new byte[EVENT_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        try (InputStream in = Files.newInputStream(device)) {
            while (!closed && in.readNBytes(raw, 0, EVENT_SIZE) == EVENT_SIZE) {
                if ((buf.getShort(16) & 0xFFFF) == EV_KEY) event(buf.getShort(18) & 0xFFFF, buf.getInt(20));
            }
        } catch (IOException | RuntimeException e) {
            // desplugado ou perdeu a permissão
        } finally {
            open.remove(device);
        }
    }

    /**
     * Um EV_KEY do kernel: valor 1 apertou, 0 soltou, 2 repetição automática (entra como aperto, igual ao
     * JNativeHook; o GlobalHotkey já ignora). Tecla que o Arara não conhece é descartada.
     */
    synchronized void event(int code, int value) {
        if (closed) return;
        boolean down = value != 0;
        int vc = NativeKeyEvent.VC_UNDEFINED, location = NativeKeyEvent.KEY_LOCATION_STANDARD;
        for (int[] m : MODIFIERS) {
            if (m[0] != code) continue;
            vc = m[1];
            location = m[2];
            if (down) modifiers |= m[3];
            else modifiers &= ~m[3];
        }
        if (vc == NativeKeyEvent.VC_UNDEFINED) vc = keyCode(code);
        if (vc == NativeKeyEvent.VC_UNDEFINED) return;
        NativeKeyEvent e = new NativeKeyEvent(
                down ? NativeKeyEvent.NATIVE_KEY_PRESSED : NativeKeyEvent.NATIVE_KEY_RELEASED,
                modifiers, code, vc, NativeKeyEvent.CHAR_UNDEFINED, location);
        if (down) sink.nativeKeyPressed(e);
        else sink.nativeKeyReleased(e);
    }

    /**
     * Código do evdev -> VC_* do JNativeHook. O bloco principal (Esc até F12) usa nos dois o scancode do conjunto 1,
     * então é o mesmo número; o teclado numérico (55, 71 a 83) fica de fora.
     */
    static int keyCode(int code) {
        Integer ext = EXTENDED.get(code);
        if (ext != null) return ext;
        boolean main = code >= 1 && code <= 70 && code != 55 || code == 87 || code == 88;
        return main ? code : NativeKeyEvent.VC_UNDEFINED;
    }

    @Override
    public void close() {
        closed = true; // as threads são daemon: a leitura bloqueada morre com o processo
    }
}
