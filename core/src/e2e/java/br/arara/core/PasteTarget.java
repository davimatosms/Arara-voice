package br.arara.core;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Janela de teste do CI (X11): espera N segundos recebendo a colagem e grava o texto num arquivo. Args: saída segundos. */
public final class PasteTarget {
    public static void main(String[] args) throws Exception {
        JTextArea area = new JTextArea();
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("PasteTarget");
            f.add(area);
            f.setSize(500, 200);
            f.setVisible(true);
            area.requestFocusInWindow();
            area.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) { System.out.println("tecla: " + e.getKeyCode() + " mods=" + e.getModifiersEx()); }
            });
            area.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { System.out.println("campo com foco"); }
                @Override public void focusLost(java.awt.event.FocusEvent e) { System.out.println("campo perdeu foco"); }
            });
        });
        Thread.sleep(Long.parseLong(args[1]) * 1000);
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> text[0] = area.getText());
        System.out.println("conteudo: [" + text[0] + "]");
        Files.writeString(Path.of(args[0]), text[0]);
        System.exit(0);
    }
}
