package br.arara.core;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;

/**
 * Cola pelo clipboard + Ctrl+V (Cmd+V no macOS) e depois devolve o que o usuário tinha copiado.
 * No macOS, o Robot só consegue apertar teclas com o Arara liberado em Acessibilidade.
 */
final class ClipboardPaster implements DefaultDictationEngine.Paster {
    // shortcut: espera fixa para o app ler o clipboard antes de restaurar; aumentar se algum app colar o texto antigo.
    private static final int RESTORE_DELAY_MS = 300;

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
            lastStrategy = LinuxPaste.press(env);
            if (lastStrategy == null) throw new LinuxPaste.CopiedOnly(); // fica no clipboard, sem restaurar
            restore(clipboard, previous);
            return;
        }
        lastStrategy = "robot";
        if (!AraraCore.MAC && !System.getProperty("os.name", "").startsWith("Windows")) waitOwnership(clipboard, text);
        Robot robot = new Robot();
        // shortcut: 10 ms entre eventos no macOS, que às vezes perde teclas seguidas; medir num Mac de verdade.
        if (AraraCore.MAC) robot.setAutoDelay(10);
        int modifier = pasteModifier(AraraCore.MAC);
        robot.keyPress(modifier);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(modifier);

        restore(clipboard, previous);
    }

    /** X11: o destino pede a seleção ao dono; espera o clipboard devolver o texto antes do Ctrl+V. */
    private static void waitOwnership(Clipboard clipboard, String text) throws InterruptedException {
        for (int i = 0; i < 15; i++) {
            try {
                if (text.equals(clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor))) break;
            } catch (Exception ignored) { // ainda sem dono: tenta de novo
            }
            Thread.sleep(20);
        }
        Thread.sleep(50);
    }

    /** Estratégia usada na última colagem (diagnóstico). */
    volatile String lastStrategy;

    private static void restore(Clipboard clipboard, Transferable previous) throws InterruptedException {
        if (previous != null) {
            Thread.sleep(RESTORE_DELAY_MS);
            clipboard.setContents(previous, null);
        }
    }
}
