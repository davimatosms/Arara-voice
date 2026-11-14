package br.arara.app;

import br.arara.core.AraraCore;
import br.arara.core.DictationEngine;
import br.arara.core.DictationListener;
import br.arara.core.DictationState;
import br.arara.core.Settings;
import br.arara.core.SettingsStore;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Primeira execução (design "11 · Primeira execução"): 6 passos numa janela de 1100x683 de conteúdo, com o
 * download do modelo em segundo plano. Posições absolutas, copiadas do design; a janela não muda de tamanho.
 */
final class Onboarding extends JPanel implements DictationListener {
    static final String[] STEPS = {"Boas-vindas", "Modelo de voz", "Microfone", "Atalho", "Primeiro ditado", "Pronto"};
    static final int WIDTH = 1100, HEIGHT = 683;
    /** Barra lateral; a área principal começa em x=272 no design, que aqui é MX dentro do conteúdo. */
    private static final int SIDE = 233, MX = 39, MW = 788;
    private static final int WELCOME = 0, MODEL = 1, MIC = 2, HOTKEY = 3, DICTATION = 4, READY = 5;
    private static final BufferedImage NUVEM = Arara.read("sprites/nuvem.png");
    private static final BufferedImage NUVEM_PEQ = Arara.read("sprites/nuvem_peq.png");
    private static final BufferedImage MORROS = Arara.read("sprites/morros.png");
    private static final BufferedImage HEAD = Arara.IMAGE.getSubimage(5, 1, 21, 18);
    private static final Color[] SKY = colors(0x1557B8, 0x1C72CF, 0x3E9BE6, 0x8FD3EC, 0xC2EF8A, 0xD8F6A0);
    private static final Color[] CLOUDY = colors(0x3A5068, 0x56708A, 0x8EA3A8, 0xB6C4A0);
    private static final String[] PHRASES = {
            "“Cria uma função que soma dois números”", "“Lembrete: revisar o pull request amanhã”",
            "“Bom dia, pessoal! A reunião mudou para as dez.”", "“Comprar pão de queijo e café no caminho”"};
    /** ~1,5 s de voz (blocos de 32 ms) para dizer "Ouvi você!" e ~2 s de zeros absolutos para dizer "bloqueado". */
    private static final int HEARD_CHUNKS = 45, BLOCKED_CHUNKS = 62;
    private static final float VOICE_LEVEL = 0.45f;

    enum Mark { DONE, CURRENT, TODO }

    static Mark mark(int step, int current) {
        return step < current ? Mark.DONE : step == current ? Mark.CURRENT : Mark.TODO;
    }

    /** Só quem nunca salvou configuração vê a primeira execução. */
    static boolean firstRun(Path dataDir) {
        return !Files.exists(dataDir.resolve("config.json"));
    }

    /** Abre o microfone para o teste ({@code AraraCore::listen}); fechar o retorno solta o microfone. */
    interface MicSource {
        AutoCloseable open(String microphone, Consumer<short[]> onChunk) throws Exception;
    }

    private final DictationEngine engine;
    private final SettingsStore settings;
    final ModelDownload download;
    private final MicSource micSource;
    private final Runnable onDone;
    private final JPanel[] pages = new JPanel[STEPS.length];
    private final Sky[] skies = new Sky[STEPS.length];
    private final Timer anim = new Timer(33, e -> tick());
    int step = -1, tick;

    // cabeçalho e rodapé (iguais em todos os passos)
    private final JLabel stepLabel = Ui.label("", tracked(Theme.extrabold(12), 1.5f), Theme.GREEN);
    private final JLabel title = Ui.label("", Theme.extrabold(32), Theme.TEXT);
    private final JLabel subtitle = Ui.label("", Theme.regular(15), Theme.TEXT_SOFT);
    private final JButton skip = new JButton("Pular por agora");
    private final JButton back = pill("Voltar", Ui.Kind.OUTLINE, 15);
    private final JButton next = pill("Continuar", Ui.Kind.PRIMARY, 15);
    private final Sidebar sidebar = new Sidebar();

    // passo 2
    private final Chip packageChip = new Chip("", Theme.TEXT_DARK, Color.WHITE, 30, 12, Theme.LIME, null);
    private final Chip waitingChip = new Chip("esperando a internet…", new Color(0xCC0B140D, true), Color.WHITE, 28, 12,
            Theme.YELLOW, null);
    private final JLabel percent = Ui.label("", Theme.display(64), Theme.TEXT);
    private final JLabel megabytes = Ui.label("", Theme.extrabold(18), Theme.TEXT);
    private final JLabel speed = Ui.label("", Theme.semibold(15), Theme.TEXT_SOFT);
    private final Chip goOn = new Chip("Pode seguir: o download continua", Theme.SELECTED, Theme.GREEN, 30, 12, Theme.GREEN, null);
    private final Bar bar = new Bar();
    private Ui.Round lightCard;
    private ErrorBox netError;

    // passo 3
    final Arara arara = new Arara(5);
    private final Waves araraWaves = new Waves(24, 4, 3, 26);
    private final Waves liveWaves = new Waves(54, 4, 4, 64);
    private final Chip heardChip = new Chip("Ouvi você!", Theme.SELECTED, Theme.GREEN, 30, 13, Theme.GREEN, null);
    private final JLabel heardNote = Ui.label("nível bom · fale normal, sem gritar", Theme.semibold(13), Theme.TEXT_SOFT);
    private final JLabel talkNote = Ui.label("Fale alguma coisa para testar…", Theme.semibold(13), Theme.TEXT_SOFT);
    private final JLabel noSignal = Ui.label("Sem sinal do microfone", Theme.bold(14), Theme.ERROR);
    private JLabel micHint;
    private ErrorBox micError;
    private AutoCloseable mic;
    private float level;
    private int voiced, zeros;
    boolean heard, blocked, anySound;

    // passo 4
    private final Chip tryChip = new Chip("Experimente agora: aperte as teclas", Theme.BG, Theme.TEXT_SOFT, 30, 12, Theme.LIME,
            Theme.BORDER);
    final Set<String> held = new HashSet<>();
    boolean hotkeyWorked;
    private Ui.Round spaceWarning;
    private final KeyEventDispatcher keyWatch = e -> {
        if (step == HOTKEY) {
            String k = keyName(e.getKeyCode());
            if (e.getID() == KeyEvent.KEY_PRESSED && k != null) held.add(k);
            if (e.getID() == KeyEvent.KEY_RELEASED) held.remove(k);
            pages[HOTKEY].repaint();
        }
        return false;
    };

    // passo 5
    final JTextPane practice = new JTextPane();
    private final Chip pasted = new Chip("Colado", Theme.SELECTED, Theme.GREEN, 26, 12, Theme.GREEN, null);
    private final JLabel pastedTime = Ui.label("", Theme.bold(12), Theme.TEXT_SOFT);
    private final JPanel waitModel = new JPanel(null);
    private final JLabel waitDetail = Ui.label("", Theme.semibold(14), Theme.TEXT_SOFT);
    private final Bar waitBar = new Bar();
    private final Chip[] suggestions = new Chip[2];
    private JButton waitLight;
    private long transcribeStart;
    private int phrase;

    // passo 6
    private final JLabel readyHint = Ui.label("", Theme.bold(17), Color.WHITE);

    Onboarding(DictationEngine engine, SettingsStore settings, ModelDownload download, MicSource micSource, Runnable onDone) {
        super(null);
        this.engine = engine;
        this.settings = settings;
        this.download = download;
        this.micSource = micSource;
        this.onDone = onDone;
        setBackground(Theme.BG);
        setPreferredSize(new Dimension(WIDTH, HEIGHT));

        sidebar.setBounds(0, 0, SIDE, HEIGHT);
        add(sidebar);
        JPanel content = new JPanel(null);
        content.setOpaque(false);
        content.setBounds(SIDE, 0, WIDTH - SIDE, HEIGHT);
        add(content);

        // Os componentes adicionados primeiro ficam por cima: cabeçalho e rodapé antes das páginas.
        put(content, stepLabel, MX, 32, 300, 18);
        put(content, title, MX, 52, 700, 44);
        put(content, subtitle, MX, 98, 760, 22);
        skip.setFont(Theme.semibold(13));
        skip.setForeground(Theme.TEXT_SOFT);
        skip.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        skip.setMargin(new Insets(2, 6, 2, 6));
        skip.setToolTipText("Usa as configurações padrão; o download continua em segundo plano");
        skip.addActionListener(e -> finish());
        Dimension sd = skip.getPreferredSize();
        put(content, skip, 711 - 6, 30 - 3, sd.width, sd.height);
        back.addActionListener(e -> show(step - 1));
        put(content, back, MX, 607, 112, 48);
        next.addActionListener(e -> {
            if (step == READY) finish();
            else show(step + 1);
        });
        next.setIcon(arrowCircle());
        next.setHorizontalTextPosition(SwingConstants.LEADING);
        next.setHorizontalAlignment(SwingConstants.LEFT);
        next.setMargin(new Insets(0, 26, 0, 6));
        put(content, new Dots(), 327, 628, 200, 6);
        put(content, next, MX + MW - 200, 607, 200, 48);

        for (int i = 0; i < pages.length; i++) {
            pages[i] = new JPanel(null);
            pages[i].setOpaque(false);
            pages[i].setVisible(false);
            put(content, pages[i], 0, 0, WIDTH - SIDE, HEIGHT);
        }
        welcome(pages[WELCOME]);
        model(pages[MODEL]);
        microphone(pages[MIC]);
        hotkey(pages[HOTKEY]);
        dictation(pages[DICTATION]);
        ready(pages[READY]);

        download.addListener(this::refresh);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyWatch);
        // Janela escondida na bandeja: solta o microfone; de volta ao passo 3, testa de novo.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (!isShowing()) {
                stopMic();
                anim.stop();
            } else if (step >= 0) {
                anim.start();
                if (step == MIC) startMic();
            }
        });
        show(WELCOME);
    }

    // ------------------------------------------------------------------ navegação

    void show(int index) {
        if (step == MIC && index != MIC) stopMic();
        step = index;
        for (int i = 0; i < pages.length; i++) pages[i].setVisible(i == index);
        boolean header = index != WELCOME && index != READY;
        stepLabel.setVisible(header);
        title.setVisible(header);
        subtitle.setVisible(header);
        stepLabel.setText("PASSO " + (index + 1) + " DE " + STEPS.length);
        skip.setVisible(index != READY);
        back.setVisible(index != WELCOME);
        int w = index == WELCOME ? 170 : 200;
        String cta = index == WELCOME ? "Começar" : index == READY ? "Começar a usar" : "Continuar";
        next.setText(cta);
        next.setBounds(MX + MW - w, 607, w, 48);
        Insets in = next.getInsets();
        next.setIconTextGap(w - in.left - in.right - 36 - next.getFontMetrics(next.getFont()).stringWidth(cta));
        if (getRootPane() != null) getRootPane().setDefaultButton(next); // Enter = Começar / Continuar
        if (index == MIC) startMic();
        if (index == HOTKEY) held.clear();
        refresh();
        if (isShowing()) anim.start();
        if (index == DICTATION) SwingUtilities.invokeLater(practice::requestFocusInWindow);
    }

    /** Concluir ou pular: grava a configuração (é o que marca a primeira execução como feita) e vai para o Início. */
    private void finish() {
        stopMic();
        anim.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyWatch);
        settings.save(settings.load());
        onDone.run();
    }

    /** A janela ganhou ou perdeu o foco. Voltando das configurações do Windows, o teste do microfone roda de novo. */
    void windowFocus(boolean gained) {
        held.clear();
        if (gained && step == MIC && blocked) startMic();
        repaint();
    }

    /** Relê configuração e download e atualiza os textos de todos os passos. */
    void refresh() {
        Settings s = settings.load();
        ModelDownload.Phase ph = download.phase;
        boolean paused = ph == ModelDownload.Phase.PAUSED || ph == ModelDownload.Phase.FAILED;
        boolean ready = ph == ModelDownload.Phase.READY;
        String keys = spoken(s.hotkey());
        switch (step) {
            case MODEL -> {
                title.setText(ready ? "Modelo de voz pronto" : "Baixando o modelo de voz");
                subtitle.setText(switch (s.model()) {
                    case PRECISO -> "large-v3-turbo: o mais preciso para português. Baixa uma vez só e fica no seu PC.";
                    case EQUILIBRADO -> "small: equilíbrio entre precisão e velocidade. Baixa uma vez só e fica no seu PC.";
                    case LEVE -> "base: o modelo leve, rápido de baixar. Dá para trocar depois, em Configurações.";
                });
            }
            case MIC -> {
                title.setText("Fale qualquer coisa");
                subtitle.setText("Vamos ver se o Arara está te ouvindo bem. Fale como se fosse ditar de verdade.");
            }
            case HOTKEY -> {
                title.setText("Seu atalho");
                subtitle.setText("Aperte em qualquer janela para começar a ouvir. Aperte de novo para colar o texto.");
            }
            case DICTATION -> {
                title.setText("Seu primeiro ditado");
                subtitle.setText("Clique na caixa, aperte " + keys + ", fale e aperte de novo. O texto aparece aqui sozinho.");
            }
            default -> { }
        }

        // passo 2
        int pct = (int) Math.floor(download.fraction() * 100);
        String size = download.total > 0 ? Math.round(download.total / 1e6) + " MB" : sizeOf(s.model());
        packageChip.setText(Ui.modelName(s.model()) + " · " + size);
        packageChip.setSize(packageChip.getPreferredSize());
        skies[MODEL].stops = paused ? CLOUDY : SKY;
        waitingChip.setVisible(ph == ModelDownload.Phase.PAUSED);
        packageChip.setVisible(!paused);
        fit(percent, pct + "%");
        percent.setForeground(paused ? Theme.TEXT_SOFT : Theme.TEXT);
        int infoX = Math.max(MX + 150, MX + percent.getWidth() + 22);
        megabytes.setLocation(infoX, megabytes.getY());
        speed.setLocation(infoX, speed.getY());
        if (ready) {
            fit(megabytes, (download.total > 0 ? Math.round(download.total / 1e6) + " MB" : size) + " · no seu PC");
            fit(speed, "pronto para ditar");
        } else if (paused) {
            fit(megabytes, ModelDownload.mb(download.done, download.total) + " · pausado");
            fit(speed, ph == ModelDownload.Phase.FAILED ? "não consegui carregar o modelo" : "continua sozinho quando a conexão voltar");
        } else if (download.done == 0) {
            fit(megabytes, "Preparando…");
            fit(speed, "conectando ao servidor de modelos");
        } else {
            fit(megabytes, ModelDownload.mb(download.done, download.total));
            fit(speed, ModelDownload.detail(download.speed, download.done, download.total));
        }
        goOn.setText(ready ? "Modelo pronto" : "Pode seguir: o download continua");
        goOn.check = ready;
        goOn.setSize(goOn.getPreferredSize());
        goOn.setVisible(!paused);
        bar.value = download.fraction();
        bar.paused = paused;
        lightCard.setVisible(!paused && !ready && s.model() == Settings.Model.PRECISO);
        netError.setVisible(paused);
        if (ph == ModelDownload.Phase.FAILED) {
            netError.set("Não consegui carregar o modelo", "Tente de novo. Se continuar, use o modelo leve, em Configurações.");
        } else {
            netError.set("Sem conexão com a internet",
                    "O download parou, mas nada se perdeu. Assim que a internet voltar, ele continua de onde parou.");
        }

        // passo 3
        refreshMic();

        // passo 4
        spaceWarning.setVisible(s.hotkey().endsWith("+Space"));
        tryChip.setText(hotkeyWorked ? "Funcionou! Seu atalho está pronto" : "Experimente agora: aperte as teclas");
        tryChip.check = hotkeyWorked;
        tryChip.setSize(tryChip.getPreferredSize());
        tryChip.setLocation((MW - tryChip.getWidth()) / 2, tryChip.getY());

        // passo 5: com o modelo ainda baixando, a caixa espera e mostra o progresso
        waitModel.setVisible(!ready);
        practice.setVisible(ready);
        fit(waitDetail, paused ? "Pausado: " + ModelDownload.mb(download.done, download.total) + " · esperando a internet"
                : download.done == 0 ? "Preparando o modelo…"
                : pct + "% · " + ModelDownload.mb(download.done, download.total) + " · "
                        + ModelDownload.detail(download.speed, download.done, download.total));
        waitBar.value = download.fraction();
        waitBar.paused = paused;
        waitLight.setVisible(s.model() == Settings.Model.PRECISO);
        for (int i = 0; i < suggestions.length; i++) {
            suggestions[i].setText(PHRASES[(phrase + i) % PHRASES.length]);
            suggestions[i].setSize(suggestions[i].getPreferredSize());
            if (i > 0) suggestions[i].setLocation(suggestions[i - 1].getX() + suggestions[i - 1].getWidth() + 12, suggestions[i].getY());
        }

        // passo 6
        fit(readyHint, "Aperte " + keys + " em qualquer janela e fale.");
        repaint();
    }

    private void refreshMic() {
        arara.alpha = blocked ? 0.35f : 1f;
        if (blocked) arara.sprite = Arara.FALA[arara.beak = 0];
        araraWaves.dashed = blocked;
        liveWaves.dashed = blocked;
        heardChip.check = true;
        heardChip.setVisible(heard && !blocked);
        heardNote.setVisible(heard && !blocked);
        talkNote.setVisible(!heard && !blocked);
        noSignal.setVisible(blocked);
        micHint.setVisible(!blocked);
        micError.setVisible(blocked);
        pages[MIC].repaint();
    }

    private void useLight() {
        Settings x = settings.load();
        settings.save(new Settings(x.hotkey(), Settings.Model.LEVE, x.accel(), x.microphone(), x.startWithWindows(),
                x.showPopup(), x.escCancels()));
        refresh();
    }

    // ------------------------------------------------------------------ microfone

    private void startMic() {
        stopMic();
        voiced = 0;
        zeros = 0;
        heard = false;
        blocked = false;
        anySound = false;
        try {
            mic = micSource.open(settings.load().microphone(), s -> SwingUtilities.invokeLater(() -> chunk(s)));
        } catch (Exception e) {
            blocked = true; // o Windows negou a linha ou não há microfone
        }
        refreshMic();
    }

    private void stopMic() {
        if (mic == null) return;
        try {
            mic.close();
        } catch (Exception ignored) {
            // já estava fechado
        }
        mic = null;
    }

    /** Um bloco do microfone (EDT): nível para as ondas e o bico, fala para o "Ouvi você!", zeros para o bloqueio. */
    void chunk(short[] samples) {
        if (step != MIC || blocked) return;
        level = AraraCore.level(samples);
        boolean zero = true;
        for (short v : samples) {
            if (v != 0) {
                zero = false;
                break;
            }
        }
        if (!zero) anySound = true;
        else zeros++;
        // Com a privacidade do Windows bloqueando, a linha abre mas só entrega zeros absolutos.
        if (!anySound && zeros >= BLOCKED_CHUNKS) {
            blocked = true;
            stopMic();
            refreshMic();
            return;
        }
        if (!heard && level > VOICE_LEVEL && ++voiced >= HEARD_CHUNKS) {
            heard = true;
            refreshMic();
        }
        liveWaves.push(level);
        araraWaves.push(level);
    }

    // ------------------------------------------------------------------ ditado (passos 4 e 5)

    @Override
    public void onState(DictationState state) {
        if (step == HOTKEY && state == DictationState.LISTENING) {
            engine.cancel(); // o atalho funcionou; aqui é só um teste, não precisa ditar
            hotkeyWorked = true;
            refresh();
        }
        if (step != DICTATION) return;
        switch (state) {
            case LISTENING -> {
                if (pasted.isVisible()) phrase += 2; // nova tentativa, frases novas
                pasted.setVisible(false);
                refresh();
            }
            case TRANSCRIBING -> transcribeStart = System.nanoTime();
            case PASTED -> showPasted((System.nanoTime() - transcribeStart) / 1e9);
            default -> { }
        }
    }

    void showPasted(double seconds) {
        pasted.check = true;
        pasted.setVisible(true);
        pasted.setSize(pasted.getPreferredSize());
        fit(pastedTime, String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f s", seconds));
        pastedTime.setVisible(seconds > 0);
        practice.setForeground(Theme.GREEN); // o texto pisca verde uma vez
        Timer t = new Timer(450, e -> practice.setForeground(Theme.TEXT));
        t.setRepeats(false);
        t.start();
        refresh();
    }

    // ------------------------------------------------------------------ animação

    void tick() {
        tick++;
        if (step == MIC && tick % 2 == 0) { // bico troca a cada ~70 ms, com a histerese do pop-up
            arara.voice += ((blocked ? 0 : level) - arara.voice) * 0.4f;
            arara.beak = Arara.beakFor(arara.beak, arara.voice);
            arara.sprite = Arara.FALA[arara.beak];
            arara.repaint();
        }
        if (step == MODEL) packageChip.setLocation(birdX() + 12, 156);
        if (skies[Math.max(step, 0)] != null) skies[step].repaint();
    }

    /** A arara do passo 2 cruza o céu até o "seu PC" conforme a porcentagem real (pixels pares, grade 2x). */
    private int birdX() {
        return 40 + (int) Math.round(download.fraction() * 516) / 2 * 2;
    }

    private int drift(int amplitude, double period) {
        return 3 * (int) Math.round(amplitude * Math.sin(tick / period));
    }

    // ------------------------------------------------------------------ páginas

    private void welcome(JPanel p) {
        Sky sky = put(p, new Sky(SKY, g -> {
            softCloud(g, -70, 276, 0.9f);
            px(g, MORROS, 3, -60, 258);
            px(g, NUVEM, 3, 540 + drift(3, 90), 240);
            px(g, Arara.VOO[(tick / 3) % Arara.VOO.length], 3, 530, 76 + 3 + drift(1, 15)); // flutua 6 px
            sparkle(g, 500, 80, 4, Theme.LIME);
            sparkle(g, 740, 230, 3, Color.WHITE);
        }), MX, 64, MW, 360);
        skies[WELCOME] = sky;
        line(label(sky, "VAMOS DEIXAR TUDO", Theme.display(60), Color.WHITE, 40, 40), 64);
        line(label(sky, "PRONTO EM 1 MINUTO.", Theme.display(60), Theme.LIME, 40, 104), 64);
        para(sky, "Oi! Eu sou o Arara. Três passos rápidos e você já está ditando.", Theme.bold(17), Color.WHITE, 42, 184, 390, 25);
        String[][] cards = {{"Baixar o modelo de voz", "574 MB, uma vez só"}, {"Testar o microfone", "uns 10 segundos"},
                {"Aprender o atalho", "e fazer o 1º ditado"}};
        for (int i = 0; i < cards.length; i++) {
            Ui.Round c = put(p, round(Theme.SURFACE, Theme.BORDER, 20), MX + i * 270, 444, 248, 96);
            Ui.Round n = put(c, round(Theme.LIME, null, 16), 18, 18, 32, 32);
            n.setLayout(new java.awt.BorderLayout());
            n.add(center(Ui.label(String.valueOf(i + 1), Theme.extrabold(15), Theme.TEXT_DARK)));
            label(c, cards[i][0], Theme.extrabold(15), Theme.TEXT, 62, 18);
            label(c, cards[i][1], Theme.regular(13), Theme.TEXT_SOFT, 62, 42);
        }
    }

    private void model(JPanel p) {
        Sky sky = put(p, new Sky(SKY, g -> {
            boolean paused = !packageChip.isVisible();
            for (int[] c : new int[][] {{40, 30}, {300, 18}, {600, 40}}) px(g, NUVEM_PEQ, 2, c[0] + drift(2, 120), c[1]);
            px(g, MORROS, 3, -120, 98);
            g.setColor(Theme.TEXT_DARK);
            g.fillRoundRect(676, 58, 88, 88, 40, 40);
            centered(g, "PC", Theme.display(26), Theme.LIME, 720, 72);
            centered(g, "seu PC", Theme.extrabold(11), Theme.TEXT_SOFT, 720, 112);
            if (paused) {
                px(g, NUVEM, 2, 290, 120);
                px(g, Arara.IMAGE, 3, 336, 22); // pousada esperando a internet
                return;
            }
            int x = birdX();
            for (int i = 0; i < 9 && 40 + i * 34 + 10 < x + 60; i++) {
                g.setColor(Theme.alpha(Theme.LIME, Math.round(255 * (0.15f + 0.08f * i))));
                g.fillRoundRect(40 + i * 34, 100 + (i % 2) * 6, 10, 10, 4, 4);
            }
            px(g, Arara.VOO[(tick / 3) % Arara.VOO.length], 2, x, 34);
            g.setColor(Theme.TEXT_DARK);
            g.fillRect(x + 70, 144, 2, 14);
            g.fillRect(x + 92, 144, 2, 14);
        }), MX, 140, MW, 200);
        skies[MODEL] = sky;
        put(sky, packageChip, 372, 156, 200, 30);
        Dimension wd = waitingChip.getPreferredSize();
        put(sky, waitingChip, 420, 40, wd.width, wd.height);

        percent.setText("62%");
        line(place(p, percent, MX, 354), 70);
        put(p, megabytes, MX + 150, 370, 400, 26);
        put(p, speed, MX + 150, 396, 400, 22);
        put(p, goOn, MX + MW - 270, 380, 260, 30);
        put(p, bar, MX, 438, MW, 14);

        lightCard = put(p, round(Theme.SURFACE, Theme.BORDER, 20), MX, 474, MW, 76);
        Ui.Round icon = put(lightCard, round(Theme.BG, null, 22), 16, 16, 44, 44);
        icon.setLayout(new java.awt.BorderLayout());
        icon.add(new JLabel(Ui.icon("zap", 22, Theme.YELLOW), SwingConstants.CENTER));
        label(lightCard, "Internet lenta?", Theme.extrabold(15), Theme.TEXT, 76, 16);
        label(lightCard, "Comece com o modelo leve (base · 148 MB) e troque depois, em Configurações.", Theme.regular(13),
                Theme.TEXT_SOFT, 76, 40);
        JButton light = put(lightCard, pill("Usar o leve agora", Ui.Kind.OUTLINE, 14), MW - 186, 18, 170, 40);
        light.addActionListener(e -> useLight());

        JButton retry = pill("Tentar agora", Ui.Kind.PRIMARY, 14);
        retry.addActionListener(e -> download.retryNow());
        netError = put(p, new ErrorBox(retry, 150), MX, 468, MW, 112);
    }

    private void microphone(JPanel p) {
        Ui.Round card = put(p, round(Theme.SURFACE, Theme.BORDER, 28), MX, 140, MW, 300);
        Ui.Round stage = put(card, round(Theme.BG, null, 22), 20, 20, 240, 260);
        put(stage, arara, 12, 4, 215, 230);
        put(stage, araraWaves, 34, 227, 168, 26);
        label(card, "MICROFONE", tracked(Theme.extrabold(11), 1.5f), Theme.TEXT_SOFT, 292, 28);
        JComboBox<String> choice = SettingsPage.micChoice(settings.load(), m -> {
            Settings x = settings.load();
            settings.save(new Settings(x.hotkey(), x.model(), x.accel(), m, x.startWithWindows(), x.showPopup(), x.escCancels()));
            startMic();
        });
        choice.setFont(Theme.semibold(14));
        choice.putClientProperty(FlatClientProperties.STYLE, "arc:28; background:" + Theme.hex(Theme.BG)
                + "; buttonBackground:" + Theme.hex(Theme.BG) + "; padding:0,10,0,6");
        put(card, choice, 292, 50, 470, 46);
        label(card, "AO VIVO", tracked(Theme.extrabold(11), 1.5f), Theme.TEXT_SOFT, 292, 124);
        Ui.Round live = put(card, round(Theme.BG, null, 16), 292, 146, 470, 84);
        put(live, liveWaves, 18, 10, 432, 64);
        heardChip.check = true;
        Dimension hd = heardChip.getPreferredSize();
        put(card, heardChip, 292, 244, hd.width, hd.height);
        place(card, heardNote, 292 + hd.width + 10, 250);
        place(card, talkNote, 292, 250);
        place(card, noSignal, 292, 246);
        micHint = label(p, "Nada aparece? Troque o microfone acima ou verifique se ele não está no mudo.", Theme.regular(13),
                Theme.TEXT_SOFT, MX, 462);
        JButton again = pill("Testar de novo", Ui.Kind.OUTLINE, 14);
        again.addActionListener(e -> startMic());
        JButton open = pill("Abrir configurações", Ui.Kind.PRIMARY, 14);
        open.addActionListener(e -> {
            try {
                new ProcessBuilder("explorer.exe", "ms-settings:privacy-microphone").start();
            } catch (Exception ignored) {
                // sem o app Configurações: o texto da caixa diz onde ir
            }
        });
        micError = put(p, new ErrorBox(again, 140, open, 180), MX, 456, MW, 112);
        micError.set("O Windows está bloqueando o microfone",
                "Em Privacidade e segurança › Microfone, ligue “Permitir que aplicativos da área de trabalho acessem o microfone”.");
    }

    private void hotkey(JPanel p) {
        Ui.Round card = put(p, round(Theme.SURFACE, Theme.BORDER, 28), MX, 140, MW, 290);
        Dimension td = tryChip.getPreferredSize();
        put(card, tryChip, (MW - td.width) / 2, 28, td.width, td.height);
        put(card, new Keys(), 0, 80, MW, 112);
        JButton change = put(card, pill("Trocar atalho", Ui.Kind.OUTLINE, 14), (MW - 170) / 2, 218, 170, 44);
        change.addActionListener(e -> SettingsPage.recordHotkey(change, hk -> {
            Settings x = settings.load();
            settings.save(new Settings(hk, x.model(), x.accel(), x.microphone(), x.startWithWindows(), x.showPopup(), x.escCancels()));
            hotkeyWorked = false;
            refresh();
        }));
        spaceWarning = put(p, round(Theme.AMBER_BG, Theme.AMBER_BORDER, 16), MX, 450, MW, 60);
        put(spaceWarning, bang(Theme.YELLOW, 18), 16, 14, 32, 32);
        label(spaceWarning, "Se este atalho digitar um espaço em algum app, troque por outro.", Theme.bold(15),
                Theme.AMBER_TEXT, 62, 19);
    }

    private void dictation(JPanel p) {
        int g = Glow.MARGIN;
        Glow box = put(p, new Glow(), MX - g, 140 - g, MW + 2 * g, 230 + 2 * g);
        label(box, "CAIXA DE TREINO", tracked(Theme.extrabold(11), 1.5f), Theme.TEXT_SOFT, g + 24, g + 22);
        put(box, pasted, g + MW - 210, g + 18, pasted.getPreferredSize().width, 26);
        pasted.setVisible(false);
        place(box, pastedTime, g + MW - 92, g + 24);
        pastedTime.setVisible(false);

        practice.setOpaque(false);
        practice.setBorder(null);
        practice.setFont(Theme.semibold(22));
        practice.setForeground(Theme.TEXT);
        practice.setCaretColor(Theme.GREEN);
        practice.getAccessibleContext().setAccessibleName("Caixa de treino");
        practice.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "O texto ditado aparece aqui.");
        SimpleAttributeSet spacing = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(spacing, 34f / practice.getFontMetrics(practice.getFont()).getHeight() - 1);
        practice.setParagraphAttributes(spacing, false);
        put(box, practice, g + 24, g + 56, MW - 48, 160);

        waitModel.setOpaque(false);
        put(box, waitModel, g + 24, g + 60, MW - 48, 150);
        label(waitModel, "Esperando o modelo de voz terminar de baixar", Theme.extrabold(20), Theme.TEXT, 0, 0);
        put(waitModel, waitDetail, 0, 34, MW - 48, 22);
        put(waitModel, waitBar, 0, 70, MW - 48, 10);
        waitLight = put(waitModel, pill("Usar o leve agora", Ui.Kind.OUTLINE, 14), 0, 100, 170, 40);
        waitLight.addActionListener(e -> useLight());

        label(p, "TENTE DIZER", tracked(Theme.extrabold(11), 1.5f), Theme.TEXT_SOFT, MX, 392);
        for (int i = 0; i < suggestions.length; i++) {
            suggestions[i] = new Chip(PHRASES[i], Theme.SURFACE, Theme.TEXT, 34, 13, null, Theme.BORDER);
            put(p, suggestions[i], MX, 414, 300, 34);
        }
    }

    private void ready(JPanel p) {
        Sky sky = put(p, new Sky(SKY, g -> {
            px(g, MORROS, 3, -40, 234);
            px(g, NUVEM, 3, 500 + drift(3, 90), 200);
            px(g, tick % 120 < 3 ? Arara.PISCAR : Arara.IMAGE, 4, 556, 40); // pousa e pisca
            sparkle(g, 470, 70, 4, Theme.LIME);
            sparkle(g, 720, 120, 3, Color.WHITE);
        }), MX, 32, MW, 300);
        skies[READY] = sky;
        line(label(sky, "TUDO", Theme.display(72), Color.WHITE, 40, 36), 76);
        line(label(sky, "PRONTO.", Theme.display(72), Theme.LIME, 40, 110), 76);
        place(sky, readyHint, 42, 190);

        Ui.Round tray = put(p, round(Theme.SURFACE, Theme.BORDER, 20), MX, 352, 386, 196);
        put(tray, new TrayMock(), 20, 20, 346, 48);
        label(tray, "O Arara fica aqui na bandeja", Theme.extrabold(16), Theme.TEXT, 20, 86);
        para(tray, "Fechar a janela não fecha o app. Clique no ícone para abrir; botão direito para sair.", Theme.regular(13),
                Theme.TEXT_SOFT, 20, 114, 346, 19);

        Ui.Round boot = put(p, round(Theme.SURFACE, Theme.BORDER, 20), MX + 402, 352, 386, 196);
        label(boot, "Iniciar com o Windows", Theme.extrabold(16), Theme.TEXT, 20, 24);
        para(boot, "Abre sozinho na bandeja quando você liga o PC, sem janela na frente.", Theme.regular(13), Theme.TEXT_SOFT,
                20, 52, 260, 19);
        Chip on = new Chip("Ligado por padrão", Theme.SELECTED, Theme.GREEN, 26, 12, Theme.GREEN, null);
        put(boot, on, 20, 140, on.getPreferredSize().width, 26);
        put(boot, Ui.toggle(settings.load().startWithWindows(), "Iniciar com o Windows", value -> {
            Settings x = settings.load();
            settings.save(new Settings(x.hotkey(), x.model(), x.accel(), x.microphone(), value, x.showPopup(), x.escCancels()));
            on.setVisible(value);
        }), 386 - 20 - 50, 23, 50, 30);
    }

    // ------------------------------------------------------------------ peças

    static String spoken(String hotkey) {
        return hotkey.replace("Space", "Espaço").replace("+", " + ");
    }

    private static String sizeOf(Settings.Model m) {
        return switch (m) {
            case LEVE -> "148 MB";
            case EQUILIBRADO -> "488 MB";
            case PRECISO -> "574 MB";
        };
    }

    /** Nome da tecla no formato do atalho ("Ctrl", "Shift", "Space", "D"...). */
    static String keyName(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_CONTROL -> "Ctrl";
            case KeyEvent.VK_SHIFT -> "Shift";
            case KeyEvent.VK_ALT -> "Alt";
            default -> SettingsPage.hotkey(0, keyCode);
        };
    }

    private static Color[] colors(int... rgb) {
        Color[] c = new Color[rgb.length];
        for (int i = 0; i < rgb.length; i++) c[i] = new Color(rgb[i]);
        return c;
    }

    private static Font tracked(Font f, float px) {
        return f.deriveFont(Map.of(TextAttribute.TRACKING, px / f.getSize2D()));
    }

    private static <T extends Component> T put(JComponent parent, T c, int x, int y, int w, int h) {
        c.setBounds(x, y, w, h);
        parent.add(c);
        return c;
    }

    /** Rótulo no tamanho do texto, com o topo em y (como as caixas de texto do FigCraft). */
    private static JLabel label(JComponent parent, String s, Font f, Color c, int x, int y) {
        return place(parent, Ui.label(s, f, c), x, y);
    }

    private static JLabel place(JComponent parent, JLabel l, int x, int y) {
        Dimension d = l.getPreferredSize();
        return put(parent, l, x, y, d.width, d.height);
    }

    private static void fit(JLabel l, String s) {
        l.setText(s);
        l.setSize(l.getPreferredSize());
    }

    /** Centraliza o texto numa linha de altura lh, como o FigCraft faz com lineHeight. */
    private static JLabel line(JLabel l, int lh) {
        l.setLocation(l.getX(), l.getY() + (lh - l.getHeight()) / 2);
        return l;
    }

    /** Parágrafo com quebra de linha e entrelinha lh (px). */
    private static JTextPane para(JComponent parent, String s, Font f, Color c, int x, int y, int w, int lh) {
        JTextPane t = new JTextPane();
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setFont(f);
        t.setForeground(c);
        int fh = t.getFontMetrics(f).getHeight();
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(a, (float) lh / fh - 1);
        t.setParagraphAttributes(a, false);
        t.setText(s);
        t.setSize(w, Short.MAX_VALUE);
        int h = t.getPreferredSize().height;
        return put(parent, t, x, y + (lh - fh) / 2, w, h);
    }

    private static Ui.Round round(Color bg, Color border, int radius) {
        return new Ui.Round(null, bg, border, radius * 2);
    }

    private static JPanel center(JComponent c) {
        JPanel p = new JPanel(new java.awt.GridBagLayout());
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    /** Círculo com "!" (avisos e erros). */
    private static Ui.Round bang(Color bg, int size) {
        Ui.Round r = round(bg, null, 99);
        r.setLayout(new java.awt.BorderLayout());
        r.add(center(Ui.label("!", Theme.extrabold(size), Theme.TEXT_DARK)));
        return r;
    }

    private static JButton pill(String text, Ui.Kind kind, int size) {
        JButton b = Ui.button(text, null, kind);
        b.setFont(Theme.extrabold(size));
        b.setMargin(new Insets(0, 8, 0, 8));
        return b;
    }

    /** Círculo escuro com a seta, à direita da pílula verde. */
    private static Icon arrowCircle() {
        FlatSVGIcon arrow = Ui.icon("arrow-right", 20, Theme.GREEN);
        return new Icon() {
            public int getIconWidth() { return 36; }
            public int getIconHeight() { return 36; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Ui.smooth(g);
                g2.setColor(Theme.TEXT_DARK);
                g2.fillOval(x, y, 36, 36);
                arrow.paintIcon(c, g2, x + 8, y + 8);
                g2.dispose();
            }
        };
    }

    /** Sprite em escala inteira, sem suavizar. */
    private static void px(Graphics2D g, BufferedImage img, int k, int x, int y) {
        g.drawImage(img, x, y, img.getWidth() * k, img.getHeight() * k, null);
    }

    /** Brilho pixel em cruz (3x3 quadrados de lado s). */
    private static void sparkle(Graphics2D g, int x, int y, int s, Color c) {
        g.setColor(c);
        g.fillRect(x, y + s, 3 * s, s);
        g.fillRect(x + s, y, s, 3 * s);
    }

    /** Nuvem fofa (bolhas brancas) do canto do céu de boas-vindas. */
    private static void softCloud(Graphics2D g, int x, int y, float k) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(255, 255, 255, 230));
        Area cloud = new Area(new RoundRectangle2D.Float(x + 30 * k, y + 70 * k, 190 * k, 38 * k, 38 * k, 38 * k));
        for (int[] b : new int[][] {{40, 70, 40}, {95, 45, 55}, {160, 50, 48}, {210, 72, 36}, {130, 80, 40}}) {
            cloud.add(new Area(new java.awt.geom.Ellipse2D.Float(x + (b[0] - b[2]) * k, y + (b[1] - b[2]) * k, 2 * b[2] * k, 2 * b[2] * k)));
        }
        g2.fill(cloud);
        g2.dispose();
    }

    private static void centered(Graphics2D g, String s, Font f, Color c, int cx, int top) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, top + fm.getAscent());
    }

    private static void text(Graphics2D g, String s, Font f, Color c, int x, int top) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x, top + g.getFontMetrics().getAscent());
    }

    private static Graphics2D pixels(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g2;
    }

    /** Selo em pílula: fundo, borda opcional, bolinha e, se check, um ✓ depois do texto. */
    static final class Chip extends JLabel {
        private final Color bg, stroke, dot;
        private final int h;
        boolean check;

        Chip(String s, Color bg, Color fg, int h, int size, Color dot, Color stroke) {
            super(s);
            this.bg = bg;
            this.stroke = stroke;
            this.dot = dot;
            this.h = h;
            setFont(Theme.bold(size));
            setForeground(fg);
            setBorder(new EmptyBorder(0, dot != null ? 30 : 14, 0, 14));
            setHorizontalTextPosition(LEADING);
            setIconTextGap(5);
            setIcon(new Icon() {
                public int getIconWidth() { return check ? 12 : 0; }
                public int getIconHeight() { return 12; }
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    if (check) Ui.icon("check", 12, fg).paintIcon(c, g, x, y);
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, h, h);
            if (stroke != null) {
                g2.setColor(stroke);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, h, h);
            }
            if (dot != null) {
                g2.setColor(dot);
                g2.fillOval(14, (getHeight() - 8) / 2, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Barra de progresso grossa com a ponta limão; amarela quando pausada. */
    static final class Bar extends JComponent {
        double value;
        boolean paused;

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth(), h = getHeight(), fw = (int) Math.round(w * Math.min(1, value));
            g2.setColor(Theme.SURFACE);
            g2.fillRoundRect(0, 0, w, h, h, h);
            if (fw > 0) {
                g2.setColor(paused ? Theme.alpha(Theme.YELLOW, 204) : Theme.GREEN);
                g2.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
                if (!paused) {
                    g2.setColor(Theme.LIME);
                    g2.fillOval(Math.max(fw, h) - h, 0, h, h);
                }
            }
            g2.dispose();
        }
    }

    /** Caixa de erro vermelha: ícone, título, explicação e botões à direita. */
    private static final class ErrorBox extends Ui.Round {
        private final JLabel title = Ui.label("", Theme.extrabold(16), Theme.TEXT);
        private JTextPane body;
        private final int bodyWidth;

        /** buttons: pares (botão, largura), da esquerda para a direita. */
        ErrorBox(Object... buttons) {
            super(null, Theme.ERROR_BG, Theme.ERROR_BORDER, 40);
            put(this, bang(Theme.ERROR, 20), 18, 18, 36, 36);
            put(this, title, 70, 18, 600, 24);
            int x = MW - 18, used = 0;
            for (int i = buttons.length - 2; i >= 0; i -= 2) {
                JButton b = (JButton) buttons[i];
                int w = (Integer) buttons[i + 1];
                x -= w;
                used += w + 10;
                if (!Theme.GREEN.equals(b.getBackground())) {
                    b.setBackground(Theme.ERROR_BG);
                    b.putClientProperty(FlatClientProperties.STYLE, b.getClientProperty(FlatClientProperties.STYLE)
                            + "; borderColor:" + Theme.hex(Theme.ERROR_BORDER) + "; hoverBorderColor:" + Theme.hex(Theme.ERROR)
                            + "; hoverBackground:" + Theme.hex(Theme.ERROR_BORDER) + "; pressedBackground:"
                            + Theme.hex(Theme.ERROR_BORDER) + "; focusedBackground:" + Theme.hex(Theme.ERROR_BG));
                }
                put(this, b, x, 34, w, 44);
                x -= 10;
            }
            bodyWidth = MW - 70 - 38 - used;
        }

        void set(String t, String b) {
            fit(title, t);
            if (body != null) remove(body);
            body = para(this, b, Theme.regular(13), Theme.ERROR_TEXT, 70, 44, bodyWidth, 19);
        }
    }

    /** Céu da landing com cantos arredondados; a cena desenha sprites em escala inteira por cima do degradê. */
    private final class Sky extends JPanel {
        Color[] stops;
        private final Consumer<Graphics2D> scene;

        Sky(Color[] stops, Consumer<Graphics2D> scene) {
            super(null);
            setOpaque(false);
            this.stops = stops;
            this.scene = scene;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = pixels(g);
            int w = getWidth(), h = getHeight();
            float[] f = new float[stops.length];
            for (int i = 0; i < f.length; i++) f[i] = i / (float) (f.length - 1);
            g2.setPaint(new LinearGradientPaint(0, 0, 0, h, f, stops));
            g2.fillRect(0, 0, w, h);
            Graphics2D s = (Graphics2D) g2.create();
            s.clipRect(0, 0, w, h);
            scene.accept(s);
            s.dispose();
            // Cantos de 24 px: pinta o fundo da janela por fora da curva, com antialias (clip seria serrilhado).
            Area outside = new Area(new Rectangle2D.Float(0, 0, w, h));
            outside.subtract(new Area(new RoundRectangle2D.Float(0, 0, w, h, 48, 48)));
            g2.setColor(Theme.BG);
            g2.fill(outside);
            g2.dispose();
        }
    }

    /** Caixa de treino: borda verde e brilho, porque ela é o foco da página (recebe o foco ao abrir o passo). */
    private static final class Glow extends JPanel {
        static final int MARGIN = 14;

        Glow() {
            super(null);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int m = MARGIN, w = getWidth() - 2 * m, h = getHeight() - 2 * m;
            for (int i = 12; i > 0; i -= 2) {
                g2.setColor(Theme.alpha(Theme.GREEN, 10));
                g2.fillRoundRect(m - i, m - i, w + 2 * i, h + 2 * i, 40 + 2 * i, 40 + 2 * i);
            }
            g2.setColor(Theme.BG);
            g2.fillRoundRect(m, m, w, h, 40, 40);
            g2.setColor(Theme.GREEN);
            g2.setStroke(new BasicStroke(2));
            g2.drawRoundRect(m + 1, m + 1, w - 2, h - 2, 38, 38);
            g2.dispose();
        }
    }

    /** Indicador de passos do rodapé: o atual comprido e limão, os feitos verdes. */
    private final class Dots extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int x = 0;
            for (int i = 0; i < STEPS.length; i++) {
                Mark m = mark(i, step);
                int w = m == Mark.CURRENT ? 36 : 14;
                g2.setColor(m == Mark.CURRENT ? Theme.LIME : m == Mark.DONE ? Theme.GREEN : Theme.BORDER);
                g2.fillRoundRect(x, 0, w, 6, 6, 6);
                x += w + 6;
            }
            g2.dispose();
        }
    }

    /** Teclas grandes do atalho; acendem (e afundam 4 px) enquanto estão apertadas. */
    private final class Keys extends JPanel {
        Keys() {
            setOpaque(false); // JPanel e não JComponent: tem contexto de acessibilidade para o nome falado
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = pixels(g);
            String[] parts = settings.load().hotkey().split("\\+");
            Font f = Theme.extrabold(30);
            int[] w = new int[parts.length];
            int total = (parts.length - 1) * 60;
            for (int i = 0; i < parts.length; i++) {
                w[i] = (int) (30 * 0.62 * label(parts[i]).length() + 64);
                total += w[i];
            }
            int x = (getWidth() - total) / 2, y = 6;
            StringBuilder spoken = new StringBuilder("Atalho:");
            for (int i = 0; i < parts.length; i++) {
                boolean lit = hotkeyWorked || held.contains(parts[i]);
                int dy = lit ? 4 : 0;
                g2.setColor(Color.BLACK);
                g2.fillRoundRect(x, y + 6, w[i], 96, 36, 36);
                g2.setColor(lit ? Theme.SELECTED : Theme.BG);
                g2.fillRoundRect(x, y + dy, w[i], 96, 36, 36);
                g2.setColor(lit ? Theme.GREEN : Theme.BORDER);
                g2.setStroke(new BasicStroke(lit ? 2 : 1.5f));
                g2.drawRoundRect(x + 1, y + dy + 1, w[i] - 2, 94, 34, 34);
                centered(g2, label(parts[i]), f, lit ? Theme.GREEN : Theme.TEXT, x + w[i] / 2, y + dy + 48 - 21);
                x += w[i];
                if (i < parts.length - 1) {
                    text(g2, "+", Theme.bold(30), Theme.TEXT_SOFT, x + 22, y + 24);
                    x += 60;
                }
                spoken.append(' ').append(label(parts[i]));
            }
            getAccessibleContext().setAccessibleName(spoken.toString());
            g2.dispose();
        }

        private String label(String key) {
            return key.equals("Space") ? "Espaço" : key;
        }
    }

    /** Bandeja do Windows desenhada, com o ícone do Arara em destaque. */
    private static final class TrayMock extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = pixels(g);
            g2.setColor(new Color(0x202020));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24);
            g2.setColor(new Color(0xCCCCCC));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawPolyline(new int[] {20, 24, 28}, new int[] {26, 22, 26}, 3);
            g2.setColor(new Color(0x5A5A5A));
            for (int i = 0; i < 3; i++) g2.fillOval(50 + i * 30, 17, 14, 14);
            g2.setColor(Theme.LIME);
            g2.drawRoundRect(145, 7, 34, 34, 20, 20);
            g2.drawImage(HEAD, 144 + (36 - HEAD.getWidth()) / 2, 6 + (36 - HEAD.getHeight()) / 2, null);
            text(g2, "14:32", Theme.semibold(12), new Color(0xDDDDDD), 290, 15);
            g2.dispose();
        }
    }

    /** Barra lateral: logo, os 6 passos e o cartão do download em segundo plano. */
    private final class Sidebar extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = pixels(g);
            g2.setColor(Theme.SURFACE);
            g2.fillRect(0, 0, SIDE - 1, getHeight());
            g2.setColor(Theme.BORDER);
            g2.fillRect(SIDE - 1, 0, 1, getHeight());
            px(g2, Arara.IMAGE, 1, 24, 22);
            text(g2, "Arara", Theme.extrabold(22), Theme.TEXT, 64, 24);
            text(g2, "primeira execução", Theme.regular(12), Theme.TEXT_SOFT, 64, 52);
            text(g2, "EM 1 MINUTO", tracked(Theme.extrabold(11), 1.5f), Theme.TEXT_SOFT, 24, 100);
            for (int i = 0; i < STEPS.length; i++) {
                Mark m = mark(i, step);
                int y = 124 + i * 48;
                if (m == Mark.CURRENT) {
                    g2.setColor(Theme.SELECTED);
                    g2.fillRoundRect(12, y, 208, 42, 42, 42);
                }
                if (m == Mark.TODO) {
                    g2.setColor(Theme.BORDER);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval(22, y + 8, 26, 26);
                } else {
                    g2.setColor(m == Mark.DONE ? Theme.GREEN : Theme.LIME);
                    g2.fillOval(22, y + 8, 26, 26);
                }
                if (m == Mark.DONE) Ui.icon("check", 16, Theme.TEXT_DARK).paintIcon(this, g2, 27, y + 13);
                else centered(g2, String.valueOf(i + 1), Theme.extrabold(13), m == Mark.CURRENT ? Theme.TEXT_DARK : Theme.TEXT_SOFT,
                        35, y + 12);
                text(g2, STEPS[i], Theme.bold(14), m == Mark.CURRENT ? Theme.GREEN : m == Mark.DONE ? Theme.TEXT : Theme.TEXT_SOFT,
                        60, y + 11);
            }

            int cx = 12, cy = 591;
            g2.setColor(Theme.BG);
            g2.fillRoundRect(cx, cy, 208, 76, 32, 32);
            g2.setColor(Theme.BORDER);
            g2.setStroke(new BasicStroke(1));
            g2.drawRoundRect(cx, cy, 208, 76, 32, 32);
            if (step == WELCOME) {
                text(g2, "3 passos rápidos", Theme.bold(14), Theme.TEXT, cx + 16, cy + 14);
                text(g2, "dá para pular e voltar depois", Theme.regular(12), Theme.TEXT_SOFT, cx + 16, cy + 38);
                g2.dispose();
                return;
            }
            text(g2, Ui.modelName(settings.load().model()), Theme.bold(14), Theme.TEXT, cx + 16, cy + 14);
            ModelDownload.Phase ph = download.phase;
            if (ph == ModelDownload.Phase.READY) {
                g2.setColor(Theme.SELECTED);
                g2.fillRoundRect(cx + 16, cy + 40, 72, 22, 22, 22);
                g2.setColor(Theme.GREEN);
                g2.fillOval(cx + 16 + 10, cy + 40 + 8, 6, 6);
                text(g2, "Pronto", Theme.bold(11), Theme.GREEN, cx + 16 + 22, cy + 43);
            } else {
                boolean paused = ph == ModelDownload.Phase.PAUSED || ph == ModelDownload.Phase.FAILED;
                String status = ph == ModelDownload.Phase.PAUSED ? "pausado · sem internet"
                        : ph == ModelDownload.Phase.FAILED ? "erro ao carregar"
                        : download.done == 0 ? "preparando…" : "baixando · " + (int) Math.floor(download.fraction() * 100) + "%";
                text(g2, status, Theme.semibold(12), paused ? Theme.YELLOW : Theme.TEXT_SOFT, cx + 16, cy + 36);
                g2.setColor(Theme.SURFACE);
                g2.fillRoundRect(cx + 16, cy + 58, 176, 5, 5, 5);
                g2.setColor(paused ? Theme.YELLOW : Theme.GREEN);
                g2.fillRoundRect(cx + 16, cy + 58, (int) Math.round(176 * download.fraction()), 5, 5, 5);
            }
            g2.dispose();
        }
    }
}
