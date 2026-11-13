package br.arara.core;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;

/** Cola pelo clipboard + Ctrl+V e depois devolve o que o usuário tinha copiado. */
final class ClipboardPaster implements DefaultDictationEngine.Paster {
    // shortcut: espera fixa para o app ler o clipboard antes de restaurar; aumentar se algum app colar o texto antigo.
    private static final int RESTORE_DELAY_MS = 300;

    @Override
    public void paste(String text) throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        clipboard.setContents(new StringSelection(text), null);

        Robot robot = new Robot();
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);

        if (previous != null) {
            Thread.sleep(RESTORE_DELAY_MS);
            clipboard.setContents(previous, null);
        }
    }
}
