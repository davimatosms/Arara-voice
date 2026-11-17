package br.arara.app;

import br.arara.core.UpdateChecker.Update;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.MatteBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Arrays;

import static br.arara.app.Onboarding.CLOUDY;
import static br.arara.app.Onboarding.MORROS;
import static br.arara.app.Onboarding.NUVEM;
import static br.arara.app.Onboarding.NUVEM_PEQ;
import static br.arara.app.Onboarding.SKY;
import static br.arara.app.Onboarding.bang;
import static br.arara.app.Onboarding.label;
import static br.arara.app.Onboarding.line;
import static br.arara.app.Onboarding.para;
import static br.arara.app.Onboarding.pill;
import static br.arara.app.Onboarding.place;
import static br.arara.app.Onboarding.put;
import static br.arara.app.Onboarding.px;
import static br.arara.app.Onboarding.round;
import static br.arara.app.Onboarding.sparkle;
import static br.arara.app.Onboarding.tracked;

/**
 * Conteúdo do diálogo de atualização (design 13): aviso, baixando, instalando e os erros. Largura fixa de 520 px e
 * posições do FigCraft, medidas a partir da linha abaixo da barra de título. Só desenha: os botões chamam {@link Actions}.
 */
final class UpdateDialog extends JPanel {
    static final int WIDTH = 520;
    private static final int PX = 28, CW = WIDTH - 2 * PX;
    enum State { OFFER, DOWNLOADING, INSTALLING, OFFLINE, CORRUPTED, PERMISSION }

    /** O que cada botão faz (o UpdateUi decide). */
    interface Actions {
        /** "Atualizar agora" e "Tentar de novo". */
        void install();
        /** "Agora não", "Fechar", Esc e o ✕. */
        void later();
        /** "Pular esta versão". */
        void skip();
        /** "Cancelar" o download. */
        void cancel();
        /** "Baixar pelo site". */
        void site();
    }

    final Update update;
    private final String current;
    private final Actions actions;
    State state = State.OFFER;
    /** Quadro da animação (40 ms): asas da arara e a barra que vai e volta. */
    int tick;
    private final Timer timer = new Timer(40, e -> {
        tick++;
        repaint();
    });
    private final JLabel percent = Ui.label("", Theme.display(52), Theme.TEXT);
    private final JLabel megabytes = Ui.label("", Theme.extrabold(16), Theme.TEXT);
    private final JLabel speed = Ui.label("", Theme.semibold(14), Theme.TEXT_SOFT);
    private final Onboarding.Bar bar = new Onboarding.Bar();
    private Onboarding.Chip chip, closing;
    private JButton primary;
    private double fraction;

    UpdateDialog(Update update, String current, Actions actions) {
        super(null);
        this.update = update;
        this.current = current == null ? "atual" : current;
        this.actions = actions;
        setBackground(Theme.BG);
        setBorder(new MatteBorder(1, 0, 0, 0, Theme.BORDER)); // divisória abaixo da barra de título
        show(State.OFFER);
    }

    /** Botão do Enter na tela atual (ou null). */
    JButton primary() {
        return primary;
    }

    /** Monta a tela do estado; a janela deve chamar pack() depois, porque a altura muda. */
    void show(State s) {
        state = s;
        removeAll();
        primary = null;
        int h = switch (s) {
            case OFFER -> offer();
            case DOWNLOADING -> downloading();
            case INSTALLING -> installing();
            default -> error();
        };
        setPreferredSize(new Dimension(WIDTH, h));
        setSize(WIDTH, h);
        revalidate();
        repaint();
    }

    /** Progresso real do download (EDT). */
    void progress(long done, long total, double bytesPerSecond) {
        fraction = total > 0 ? Math.min(1, (double) done / total) : 0;
        percent.setText(Math.round(fraction * 100) + "%");
        percent.setSize(percent.getPreferredSize());
        megabytes.setText(ModelDownload.mb(done, total));
        speed.setText(ModelDownload.detail(bytesPerSecond, done, total));
        int x = Math.max(PX + 120, percent.getX() + percent.getWidth() + 12);
        megabytes.setBounds(x, 240, megabytes.getPreferredSize().width, megabytes.getPreferredSize().height);
        speed.setBounds(x, 264, speed.getPreferredSize().width, speed.getPreferredSize().height);
        bar.value = fraction;
        if (total > 0) chip.setText(update.version() + " · " + Math.round(total / 1e6) + " MB");
        chip.setBounds(birdX() + 26, 116, chip.getPreferredSize().width, chip.getPreferredSize().height);
        repaint();
    }

    /** "Fechando em 2 s…". */
    void countdown(int seconds) {
        closing.setText("Fechando em " + seconds + " s…");
        closing.setSize(closing.getPreferredSize());
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    // ------------------------------------------------------------------ telas

    private int offer() {
        Onboarding.Sky sky = stage(SKY, g -> {
            clouds(g);
            px(g, Arara.VOO[(1 + tick / 3) % Arara.VOO.length], 2, 150, -6);
            wires(g, 150, 104, 12);
            sparkle(g, 120, 30, 4, Theme.LIME);
            sparkle(g, 352, 24, 3, Color.WHITE);
            sparkle(g, 400, 96, 3, Theme.LIME);
        });
        chip = chip(sky, "Arara " + update.version(), Theme.TEXT_DARK, Color.WHITE, 26, 12, Theme.LIME, 176, 114);
        label(this, "Nova versão " + update.version() + " disponível", Theme.extrabold(23), Theme.TEXT, PX, 192);
        label(this, "Você está na " + current + ". Quer instalar agora?", Theme.regular(14), Theme.TEXT_SOFT, PX, 226);
        int y = 264;
        String[] notes = notes(update.notes());
        if (notes.length > 0) {
            label(this, "NOVIDADES", tracked(Theme.extrabold(11), 1.5f), Theme.ACTIVE, PX, 264);
            y = 288;
            for (String n : notes) {
                put(this, round(Theme.PRIMARY, null, 3), PX + 2, y + 7, 6, 6);
                y += para(this, n, Theme.semibold(14), Theme.TEXT, PX + 18, y, CW - 18, 20).getHeight() + 6;
            }
        }
        int h = y + 98;
        buttons(h, "Pular esta versão", actions::skip, button("Agora não", false, 124, actions::later),
                button("Atualizar agora", true, 172, actions::install));
        return h;
    }

    private int downloading() {
        Onboarding.Sky sky = stage(SKY, g -> {
            clouds(g);
            int x = birdX();
            for (int i = 0; i < 6 && 18 + i * 26 + 9 < x + 20; i++) {
                g.setColor(Theme.alpha(Theme.LIME, Math.round(255 * (0.15f + 0.1f * i))));
                g.fillRoundRect(18 + i * 26, 72 + (i % 2) * 6, 9, 9, 4, 4);
            }
            px(g, Arara.VOO[(2 + tick / 3) % Arara.VOO.length], 2, x, -2);
            wires(g, x, 108, 10);
            computer(g, CW - 96);
        });
        chip = chip(sky, "Arara " + update.version(), Theme.TEXT_DARK, Color.WHITE, 26, 12, Theme.LIME, birdX() + 26, 116);
        label(this, "Baixando a versão " + update.version(), Theme.extrabold(23), Theme.TEXT, PX, 192);
        percent.setText("0%"); // altura certa antes de centralizar na linha
        line(place(this, percent, PX, 230), 58);
        add(megabytes);
        add(speed);
        put(this, bar, PX, 302, CW, 12);
        progress(0, -1, 0);
        para(this, "Pode continuar ditando. Ao terminar, o Arara confere o arquivo antes de instalar.", Theme.regular(13),
                Theme.TEXT_SOFT, PX, 328, CW, 19);
        int h = 444;
        buttons(h, null, null, button("Cancelar", false, 124, actions::cancel));
        return h;
    }

    private int installing() {
        Onboarding.Sky sky = stage(SKY, g -> {
            clouds(g);
            computer(g, CW - 120);
            px(g, Arara.IMAGE, 2, CW - 196, 44);
            sparkle(g, CW - 140, 26, 4, Theme.LIME);
            sparkle(g, CW - 30, 120, 3, Color.WHITE);
        });
        Onboarding.Chip ok = chip(sky, "Arquivo conferido", Theme.TEXT_DARK, Theme.LIME, 28, 12, null, 24, 60);
        ok.setIcon(Ui.icon("check", 12, Theme.LIME));
        ok.setHorizontalTextPosition(SwingConstants.TRAILING);
        ok.setSize(ok.getPreferredSize());
        label(this, "Instalando a versão " + update.version(), Theme.extrabold(23), Theme.TEXT, PX, 192);
        label(this, "O Arara vai fechar e abrir de novo.", Theme.bold(16), Theme.TEXT, PX, 230);
        para(this, Ui.MAC ? "Trocando o Arara em Aplicativos. Seus ditados, dicionário e configurações ficam."
                : Ui.LINUX ? "Trocando o arquivo AppImage. Seus ditados, dicionário e configurações ficam."
                : "O instalador roda sozinho, sem perguntas. Seus ditados, dicionário e configurações ficam.",
                Theme.regular(14), Theme.TEXT_SOFT, PX, 258, CW, 20);
        put(this, new JComponent() { // indeterminada: o tamanho final do instalador não é conhecido
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(Theme.SURFACE);
                g2.fillRoundRect(0, 0, getWidth(), 12, 12, 12);
                g2.setColor(Theme.HIGHLIGHT);
                g2.fillRoundRect((int) ((getWidth() - 150) * (0.5 + 0.5 * Math.sin(tick * 0.08))), 0, 150, 12, 12, 12);
                g2.dispose();
            }
        }, PX, 318, CW, 12);
        closing = chip(this, "", Theme.SELECTED, Theme.READY, 30, 13, Theme.READY, PX, 350);
        countdown(2);
        return 404;
    }

    private int error() {
        String reason, title, body;
        boolean siteOnly = siteOnly();
        switch (state) {
            case CORRUPTED -> {
                reason = "arquivo diferente";
                title = "O arquivo baixado não confere";
                body = siteOnly ? "Por segurança, o Arara apagou o arquivo e não instalou nada. Veio diferente duas vezes:"
                        + " baixe a versão nova pelo site."
                        : "Por segurança, o Arara apagou o arquivo e não instalou nada. Pode ter sido um erro no download.";
            }
            case PERMISSION -> {
                reason = "sem permissão";
                title = Ui.LINUX ? "Sem permissão para trocar o AppImage" : "O macOS não deixou trocar o app";
                body = Ui.LINUX
                        ? "Baixe a versão nova pelo site e substitua o arquivo .AppImage."
                        : "Baixe a versão nova pelo site, abra o arquivo e arraste o Arara para a pasta Aplicativos.";
            }
            default -> {
                reason = "sem internet…";
                title = "Não deu para baixar";
                body = message != null ? message
                        : "Parece que a internet caiu. Confira a conexão e tente de novo, ou baixe pelo site.";
            }
        }
        Onboarding.Sky sky = stage(CLOUDY, g -> {
            clouds(g);
            px(g, NUVEM, 2, 150, 92);
            px(g, Arara.IMAGE, 2, 196, 30);
        });
        chip(sky, reason, Theme.alpha(Theme.TEXT_DARK, 0xCC), Color.WHITE, 28, 12, Theme.SKY_YELLOW, 270, 34);
        Ui.Round box = put(this, round(Theme.ERROR_BG, Theme.ERROR_BORDER, 20), PX, 190, CW, 112);
        put(box, bang(Theme.ERROR, 19), 18, 18, 34, 34);
        line(label(box, title, Theme.extrabold(17), Theme.TEXT, 66, 18), 23);
        para(box, body, Theme.regular(13), Theme.ERROR_TEXT, 66, 48, CW - 84, 19);
        label(this, "Nada mudou: a versão " + current + " continua funcionando.", Theme.semibold(13), Theme.TEXT_SOFT, PX, 318);
        int h = 414;
        JButton site = button("Baixar pelo site", siteOnly || state == State.PERMISSION, siteOnly ? 170 : 160, actions::site);
        if (siteOnly) buttons(h, "Fechar", actions::later, site);
        else if (state == State.PERMISSION) buttons(h, "Fechar", actions::later,
                button("Tentar de novo", false, 150, actions::install), button("Baixar pelo site", true, 170, actions::site));
        else buttons(h, "Fechar", actions::later, site, button("Tentar de novo", true, 160, actions::install));
        return h;
    }

    /** Corpo do erro "sem internet" quando o problema não é a rede (mensagem do core, já em PT-BR). */
    String message;
    /** Depois de 2 arquivos que não conferem seguidos, só sobra "Baixar pelo site". */
    boolean corruptedTwice;

    private boolean siteOnly() {
        return state == State.CORRUPTED && corruptedTwice;
    }

    // ------------------------------------------------------------------ peças

    /** Notas do manifesto, uma novidade por linha. */
    static String[] notes(String notes) {
        return Arrays.stream(notes == null ? new String[0] : notes.split("\\R"))
                .map(String::strip).filter(n -> !n.isEmpty()).toArray(String[]::new);
    }

    /** Arara voa da esquerda até o "seu PC" conforme a porcentagem real (48% fica onde o design desenhou). */
    private int birdX() {
        return 20 + (int) Math.round(270 * fraction);
    }

    /** Palco: o céu da landing com a cena; os selos vão dentro dele. */
    private Onboarding.Sky stage(Color[] stops, java.util.function.Consumer<Graphics2D> scene) {
        Onboarding.Sky sky = put(this, new Onboarding.Sky(stops, scene), PX, 22, CW, 150);
        sky.arc = 40;
        return sky;
    }

    private static void clouds(Graphics2D g) {
        for (int[] c : new int[][] {{24, 16}, {250, 10}, {380, 52}}) px(g, NUVEM_PEQ, 2, c[0], c[1]);
        px(g, MORROS, 2, -40, 82);
    }

    /** Os dois fios que prendem o pacote da versão nas patas. */
    private static void wires(Graphics2D g, int birdX, int y, int h) {
        g.setColor(Theme.TEXT_DARK);
        g.fillRect(birdX + 70, y, 2, h);
        g.fillRect(birdX + 92, y, 2, h);
    }

    /** Destino: "PC / seu PC" (ou "MAC / Aplicativos"), quadrado escuro de 72 px. */
    private static void computer(Graphics2D g, int x) {
        g.setColor(Theme.TEXT_DARK);
        g.fillRoundRect(x, 38, 72, 72, 36, 36);
        FontMetrics big = g.getFontMetrics(Theme.display(22)), small = g.getFontMetrics(Theme.extrabold(10));
        int top = 38 + (72 - big.getHeight() - small.getHeight()) / 2;
        Onboarding.centered(g, Ui.MAC ? "MAC" : "PC", Theme.display(22), Theme.LIME, x + 36, top);
        Onboarding.centered(g, Ui.MAC ? "Aplicativos" : Ui.LINUX ? "AppImage" : "seu PC", Theme.extrabold(10),
                Theme.SKY_SOFT, x + 36, top + big.getHeight());
    }

    private static Onboarding.Chip chip(JComponent parent, String s, Color bg, Color fg, int h, int size, Color dot, int x, int y) {
        Onboarding.Chip c = new Onboarding.Chip(s, bg, fg, h, size, dot, null);
        return put(parent, c, x, y, c.getPreferredSize().width, h);
    }

    private JButton button(String text, boolean main, int width, Runnable action) {
        JButton b = pill(text, main ? Ui.Kind.PRIMARY : Ui.Kind.OUTLINE, 15);
        b.setSize(width, 44);
        b.addActionListener(e -> action.run());
        if (main) primary = b;
        return b;
    }

    /** Botões alinhados à direita no rodapé e, à esquerda, o link discreto. */
    private void buttons(int h, String link, Runnable onLink, JButton... items) {
        int y = h - 68, x = WIDTH - PX;
        for (int i = items.length - 1; i >= 0; i--) {
            x -= items[i].getWidth();
            put(this, items[i], x, y, items[i].getWidth(), 44);
            x -= 10;
        }
        if (link != null) {
            JButton l = Ui.link(link, Theme.semibold(13), Theme.TEXT_SOFT);
            l.addActionListener(e -> onLink.run());
            Dimension d = l.getPreferredSize();
            put(this, l, PX, y + 22 - d.height / 2, d.width, d.height);
        }
    }
}
