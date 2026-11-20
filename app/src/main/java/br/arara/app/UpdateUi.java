package br.arara.app;

import br.arara.core.AraraCore;
import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.UpdateChecker;
import br.arara.core.UpdateChecker.Update;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.TrayIcon;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A atualização na tela: decide quando e como avisar (diálogo sobre a janela em foco; senão notificação, item
 * "Atualizar para X…" e ponto verde na bandeja) e conduz o diálogo: baixar, conferir, instalar e os erros.
 * Tudo na EDT, menos o download e o "Procurar agora".
 */
final class UpdateUi implements DictationListener {
    static final String SITE = "https://arara-voice.vercel.app";

    /** O que a tela usa do {@link UpdateChecker}; o {@code --demo} troca por um de mentira. */
    interface Updates {
        String current();

        Optional<Update> latest() throws IOException;

        boolean install(Update u, AraraCore.DownloadProgress progress, BooleanSupplier cancelled) throws IOException;

        void skip(Update u);
    }

    static Updates of(UpdateChecker c) {
        return new Updates() {
            public String current() { return c.current(); }
            public Optional<Update> latest() throws IOException { return c.latest(); }
            public boolean install(Update u, AraraCore.DownloadProgress p, BooleanSupplier x) throws IOException {
                return c.install(u, p, x);
            }
            public void skip(Update u) { c.skip(u); }
        };
    }

    enum Form { DIALOG, NOTIFICATION, ON_FOCUS }

    /** Janela aberta e em foco: diálogo sobre ela. Senão nada abre na frente do que a pessoa digita. */
    static Form form(boolean visible, boolean focused, boolean tray) {
        return visible && focused ? Form.DIALOG : tray ? Form.NOTIFICATION : Form.ON_FOCUS;
    }

    /**
     * Quando avisar: nunca durante um ditado (espera acabar) e no máximo uma vez por versão por dia, então "Agora não"
     * só volta no dia seguinte. Conta também os arquivos que não conferem seguidos.
     */
    static final class Policy {
        // shortcut: só em memória, então reabrir o Arara no mesmo dia avisa de novo; persistir no config.json se incomodar
        private final Map<String, LocalDate> warned = new HashMap<>();
        private Update waiting;
        private boolean dictating;
        private int corrupted;

        /** Versão nova da verificação: devolve a que deve ser avisada agora, ou null. */
        Update offer(Update u, LocalDate today) {
            if (dictating) {
                waiting = u;
                return null;
            }
            if (today.equals(warned.get(u.version()))) return null;
            warned.put(u.version(), today);
            return u;
        }

        /** Estado do ditado: ao terminar, devolve a versão que esperava (ou null). */
        Update state(DictationState s, LocalDate today) {
            dictating = s == DictationState.LISTENING || s == DictationState.SILENCE || s == DictationState.TRANSCRIBING
                    || s == DictationState.AWAITING_APPROVAL;
            if (dictating || waiting == null) return null;
            Update u = waiting;
            waiting = null;
            return offer(u, today);
        }

        /** Mais um arquivo que não confere; true a partir do segundo seguido (só sobra o site). */
        boolean corrupted() {
            return ++corrupted >= 2;
        }

        void downloaded() {
            corrupted = 0;
        }
    }

    private final JFrame frame;
    private final Updates updates;
    private final TrayIcon tray;
    private final Runnable quit;
    private final Policy policy = new Policy();
    private java.awt.Image icon;
    private Update pending;
    private MenuItem item;
    private long notifiedAt;
    private JDialog window;
    private UpdateDialog dialog;
    private Component glass;
    private volatile boolean cancelled;
    private volatile long lastPost;

    /**
     * @param tray null sem bandeja
     * @param quit fecha o Arara depois de disparar o instalador (System.exit no app de verdade)
     */
    UpdateUi(JFrame frame, Updates updates, TrayIcon tray, Runnable quit) {
        this.frame = frame;
        this.updates = updates;
        this.tray = tray;
        this.quit = quit;
        this.icon = tray != null ? tray.getImage() : null;
    }

    String current() {
        return updates.current();
    }

    /** Troca o ícone da bandeja (chave do ícone da arara), mantendo o ponto de "atualização pendente". */
    void trayIcon(java.awt.Image image) {
        icon = image;
        if (tray != null) tray.setImage(pending != null && item != null ? dotted(icon) : icon);
    }

    /** Resultado da verificação automática (EDT). */
    void offer(Update u) {
        Update now = policy.offer(u, LocalDate.now());
        if (now != null) present(now);
    }

    @Override
    public void onState(DictationState s) {
        Update u = policy.state(s, LocalDate.now());
        if (u != null) present(u);
    }

    private void present(Update u) {
        pending(u);
        switch (form(frame.isVisible(), frame.isFocused(), tray != null)) {
            case DIALOG -> open(u);
            case NOTIFICATION -> {
                String first = UpdateDialog.notes(u.notes()).length > 0 ? UpdateDialog.notes(u.notes())[0] : "";
                tray.displayMessage("Nova versão " + u.version() + " disponível",
                        (first.isEmpty() ? "" : first + "\n") + "Clique para ver e instalar.", TrayIcon.MessageType.NONE);
                notifiedAt = System.currentTimeMillis();
            }
            case ON_FOCUS -> frame.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    frame.removeWindowFocusListener(this);
                    if (pending != null) open(pending);
                }
            });
        }
    }

    /**
     * Clique no ícone da bandeja: se acabou de sair a notificação, foi nela (o AWT não diferencia) e abre o diálogo.
     *
     * @return true se tratou o clique
     */
    boolean notificationClicked() {
        // shortcut: 15 s depois da notificação, um clique no ícone conta como clique nela
        if (pending == null || System.currentTimeMillis() - notifiedAt > 15_000) return false;
        open(pending);
        return true;
    }

    /** "Procurar agora" das Configurações; os callbacks rodam na EDT. */
    void search(Consumer<Optional<Update>> found, Consumer<IOException> failed) {
        Thread t = new Thread(() -> {
            try {
                Optional<Update> u = updates.latest();
                SwingUtilities.invokeLater(() -> {
                    u.ifPresent(this::pending);
                    found.accept(u);
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> failed.accept(e));
            }
        }, "arara-procurar-atualizacao");
        t.setDaemon(true);
        t.start();
    }

    /** Ponto verde no ícone e "Atualizar para X…" no menu, até a pessoa decidir. */
    private void pending(Update u) {
        pending = u;
        if (tray == null) return;
        if (item == null) {
            item = new MenuItem();
            item.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                if (pending != null) open(pending);
            }));
            tray.getPopupMenu().insert(item, 1);
        }
        item.setLabel("Atualizar para " + u.version() + "…");
        tray.setImage(dotted(icon));
    }

    private void resolved() {
        pending = null;
        if (tray == null || item == null) return;
        tray.getPopupMenu().remove(item);
        item = null;
        tray.setImage(icon);
    }

    /** Ícone da bandeja com o ponto na cor da arara no canto de cima. */
    static BufferedImage dotted(java.awt.Image icon) {
        int w = icon.getWidth(null), h = icon.getHeight(null), d = Math.max(4, Math.min(w, h) * 2 / 5);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Ui.smooth(img.getGraphics());
        g.drawImage(icon, 0, 0, null);
        g.setColor(Theme.PRIMARY);
        g.fillOval(w - d, 0, d, d);
        g.dispose();
        return img;
    }

    /** Abre o diálogo: modal sobre a janela em foco (com véu escuro), ou sozinho no centro da tela. */
    void open(Update u) {
        if (window != null) {
            window.toFront();
            return;
        }
        boolean over = frame.isVisible() && frame.isFocused();
        dialog = new UpdateDialog(u, updates.current(), actions(u));
        window = new JDialog(over ? frame : null, "Atualização do Arara",
                over ? Dialog.ModalityType.DOCUMENT_MODAL : Dialog.ModalityType.MODELESS);
        window.setIconImages(frame.getIconImages());
        decorate(window.getRootPane());
        window.setContentPane(dialog);
        window.setResizable(false);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                escape();
            }
        });
        window.getRootPane().registerKeyboardAction(e -> escape(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        layout();
        window.setLocationRelativeTo(over ? frame : null);
        if (over) {
            glass = frame.getGlassPane();
            JComponent veil = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(new Color(0, 0, 0, 140));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            frame.setGlassPane(veil);
            veil.setVisible(true);
        }
        window.setVisible(true); // modal: volta só quando fechar
    }

    /** Barra de título como no design: fundo da superfície, 36 px, sem ícone (FlatLaf no Windows; o Mac usa a nativa). */
    static void decorate(JRootPane root) {
        root.putClientProperty(FlatClientProperties.TITLE_BAR_BACKGROUND, Theme.SURFACE);
        root.putClientProperty(FlatClientProperties.TITLE_BAR_FOREGROUND, Theme.TEXT_SOFT);
        root.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, 36);
        root.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
    }

    private UpdateDialog.Actions actions(Update u) {
        return new UpdateDialog.Actions() {
            public void install() { download(u); }
            public void later() { close(); }
            public void skip() {
                updates.skip(u);
                resolved();
                close();
            }
            public void cancel() {
                cancelled = true;
                close();
            }
            public void site() {
                try {
                    Desktop.getDesktop().browse(URI.create(SITE));
                } catch (Exception ignored) {
                    // sem navegador: o texto do erro já diz o site
                }
                close();
            }
        };
    }

    private void escape() {
        if (dialog == null) return;
        switch (dialog.state) {
            case DOWNLOADING -> {
                cancelled = true;
                close();
            }
            case INSTALLING -> { } // já disparou o instalador: só falta fechar
            default -> close();
        }
    }

    private void layout() {
        window.pack();
        JRootPane root = window.getRootPane();
        root.setDefaultButton(dialog.primary());
    }

    private void show(UpdateDialog.State s) {
        dialog.show(s);
        layout();
    }

    private void download(Update u) {
        UpdateDialog d = dialog;
        cancelled = false;
        show(UpdateDialog.State.DOWNLOADING);
        ModelDownload.Rate rate = new ModelDownload.Rate();
        Thread t = new Thread(() -> {
            try {
                boolean ok = updates.install(u, (done, total) -> {
                    long now = System.nanoTime();
                    if (now - lastPost < 100_000_000L && done != total) return; // tela a cada 100 ms
                    lastPost = now;
                    SwingUtilities.invokeLater(() -> {
                        if (dialog == d) d.progress(done, total, rate.add(now, done));
                    });
                }, () -> cancelled);
                SwingUtilities.invokeLater(() -> {
                    if (dialog != d) return;
                    if (ok) installing();
                    else close(); // cancelado, ou rodando fora do app instalado (nada foi feito)
                });
            } catch (UpdateChecker.ManualInstallNeeded e) {
                failed(d, UpdateDialog.State.PERMISSION, null);
            } catch (UpdateChecker.Corrupted e) {
                SwingUtilities.invokeLater(() -> {
                    if (dialog != d) return;
                    d.corruptedTwice = policy.corrupted();
                    show(UpdateDialog.State.CORRUPTED);
                });
            } catch (IOException e) {
                if (!cancelled) failed(d, UpdateDialog.State.OFFLINE, network(e) ? null : e.getMessage());
            }
        }, "arara-atualizacao-download");
        t.setDaemon(true);
        t.start();
    }

    private void failed(UpdateDialog d, UpdateDialog.State s, String message) {
        SwingUtilities.invokeLater(() -> {
            if (dialog != d) return;
            d.message = message;
            show(s);
        });
    }

    /** Arquivo conferido e instalador disparado: 2 s de contagem e fecha. */
    private void installing() {
        policy.downloaded();
        show(UpdateDialog.State.INSTALLING);
        int[] left = {2};
        Timer t = new Timer(1000, null);
        t.addActionListener(e -> {
            if (--left[0] > 0) {
                if (dialog != null) dialog.countdown(left[0]);
                return;
            }
            t.stop();
            resolved();
            close();
            quit.run();
        });
        t.start();
    }

    private void close() {
        if (window == null) return;
        window.dispose();
        window = null;
        dialog = null;
        if (glass != null) {
            frame.getGlassPane().setVisible(false);
            frame.setGlassPane(glass);
            glass = null;
        }
    }

    /** Falha de rede (sem internet, DNS, tempo esgotado): o erro mostra o texto do design em vez da mensagem técnica. */
    static boolean network(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketException || t instanceof UnknownHostException || t instanceof HttpTimeoutException
                    || t instanceof InterruptedIOException) return true;
        }
        return e.getMessage() == null || e.getMessage().startsWith("Download interrompido");
    }
}
