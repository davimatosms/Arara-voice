package br.arara.core;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cola pelo clipboard + Ctrl+V (Cmd+V no macOS) e depois devolve o que o usuário tinha copiado.
 * No macOS, o Robot só consegue apertar teclas com o Arara liberado em Acessibilidade.
 */
final class ClipboardPaster implements DefaultDictationEngine.Paster {
    // Windows lê o clipboard na hora do Ctrl+V; 300 ms bastam. macOS (apps Electron leem a tecla e só depois pedem o
    // pasteboard: restaurar cedo cola o texto antigo) e X11 (a seleção é servida pelo dono) esperam mais.
    private static final int RESTORE_DELAY_MS = 300;
    private static final int SLOW_RESTORE_DELAY_MS = 1000;
    // A espera roda fora da thread de transcrição: sem ela, um ditado começado logo depois do colar atrasava.
    private final ExecutorService restorer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arara-clipboard");
        t.setDaemon(true);
        return t;
    });

    /** Linux: colar com Ctrl+Shift+V (Configurações › "Colar com"). Fora do Linux é ignorado. */
    private final java.util.function.BooleanSupplier shift;

    ClipboardPaster() {
        this(() -> false);
    }

    ClipboardPaster(java.util.function.BooleanSupplier shift) {
        this.shift = shift;
    }

    /** Modificador do colar: Cmd no macOS, Ctrl no resto. */
    static int pasteModifier(boolean mac) {
        return mac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    }

    @Override
    public void paste(String text) throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        clipboard.setContents(new StringSelection(text), null);

        if (AraraCore.wayland()) { // X11 e o resto seguem o Robot abaixo, sem mudança
            LinuxPaste.Env env = new LinuxPaste.SystemEnv();
            LinuxPaste.wlCopy(env, text);
            lastStrategy = LinuxPaste.press(env, shift.getAsBoolean());
            if (lastStrategy == null) throw new LinuxPaste.CopiedOnly(); // fica no clipboard, sem restaurar
            restore(clipboard, previous, text, SLOW_RESTORE_DELAY_MS);
            return;
        }
        lastStrategy = "robot";
        boolean mac = AraraCore.MAC;
        // Windows entrega o clipboard na hora; macOS e X11 conferem se o texto novo já vale antes do atalho.
        if (!System.getProperty("os.name", "").startsWith("Windows")) waitOwnership(clipboard, text);
        Robot robot = new Robot();
        // shortcut: 10 ms entre eventos no macOS, que às vezes perde teclas seguidas; medir num Mac de verdade.
        if (mac) robot.setAutoDelay(10);
        int modifier = pasteModifier(mac);
        boolean withShift = AraraCore.LINUX && shift.getAsBoolean(); // X11: terminais colam com Ctrl+Shift+V
        robot.keyPress(modifier);
        if (withShift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        if (withShift) robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.keyRelease(modifier);

        restore(clipboard, previous, text, mac || AraraCore.LINUX ? SLOW_RESTORE_DELAY_MS : RESTORE_DELAY_MS);
    }

    /** X11 e macOS: espera o clipboard devolver o texto novo antes de mandar o atalho de colar. */
    private static void waitOwnership(Clipboard clipboard, String text) throws InterruptedException {
        for (int i = 0; i < 15; i++) {
            try {
                if (text.equals(clipboard.getData(DataFlavor.stringFlavor))) break;
            } catch (Exception ignored) { // ainda sem dono: tenta de novo
            }
            Thread.sleep(20);
        }
        Thread.sleep(50);
    }

    /** Estratégia usada na última colagem (diagnóstico). */
    volatile String lastStrategy;

    /**
     * Devolve o que o usuário tinha copiado, depois da espera para o app de destino ler o texto colado. Só restaura
     * se o clipboard ainda tiver esse texto: se a pessoa já copiou outra coisa, não sobrescreve.
     */
    private void restore(Clipboard clipboard, Transferable previous, String text, int delayMs) {
        if (previous == null) return;
        restorer.execute(() -> {
            try {
                Thread.sleep(delayMs);
                if (!text.equals(clipboard.getData(DataFlavor.stringFlavor))) return;
                clipboard.setContents(previous, null);
            } catch (Exception ignored) { // a restauração é cortesia: o texto colado não depende dela
            }
        });
    }
}
