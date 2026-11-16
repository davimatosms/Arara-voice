package br.arara.app;

import br.arara.core.Settings.Macaw;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Cores do tema ativo (uma arara num modo, de temas.properties), fontes Nunito Sans e Anton e configuração do FlatLaf.
 * Único lugar com cores. As cores são tokens: lidas na hora de pintar, então trocar o tema não exige refazer as telas.
 */
public final class Theme {
    /** Tokens de um tema; os nomes em português são os de temas.properties e de arte/temas.py. */
    record Palette(Macaw macaw, boolean light, int bg, int surface, int border, int text, int textSoft, int primary,
            int onPrimary, int accent, int onAccent, int support, int ready) {}

    private static final Properties DATA = load();
    static Palette active = palette(Macaw.CANINDE, false);

    public static final Color BG = token(Palette::bg);
    public static final Color SURFACE = token(Palette::surface);
    public static final Color BORDER = token(Palette::border);
    public static final Color TEXT = token(Palette::text);
    public static final Color TEXT_SOFT = token(Palette::textSoft);
    /** Cor da arara: botão principal, chaves ligadas, ondas, notinhas. */
    public static final Color PRIMARY = token(Palette::primary);
    public static final Color ON_PRIMARY = token(Palette::onPrimary);
    public static final Color PRIMARY_HOVER = token(p -> p.light ? mix(p.primary, 0x000000, 0.12) : mix(p.primary, 0xFFFFFF, 0.3));
    /** Selo "Ouvindo", cursor do texto ao vivo, botão secundário. */
    public static final Color ACCENT = token(Palette::accent);
    public static final Color ON_ACCENT = token(Palette::onAccent);
    /** "Transcrevendo", avisos e "Opcional". */
    public static final Color SUPPORT = token(Palette::support);
    /** "Pronto", "Colado", "Ligado". */
    public static final Color READY = token(Palette::ready);
    /** Texto e ícone do item ativo e borda do selecionado: a cor da arara no escuro, a de "Pronto" no claro. */
    public static final Color ACTIVE = token(p -> p.light ? p.ready : p.primary);
    /** Destaque da primeira execução (passo atual): o lima do design original só na canindé escura. */
    public static final Color HIGHLIGHT = token(p -> canindeDark(p) ? 0xC8F56A : p.primary);
    public static final Color ON_HIGHLIGHT = token(p -> canindeDark(p) ? 0x0B140D : p.onPrimary);
    /** Fundo de selo ou tecla acesa: a cor da arara misturada na superfície. */
    public static final Color SELECTED = token(p -> mix(p.surface, p.primary, 0.15));
    // Erro e aviso: iguais em todos os temas escuros; no claro, versões escuras para o texto ler bem.
    public static final Color ERROR = token(p -> p.light ? 0xC0392B : 0xF0705A);
    /** Texto sobre ERROR ou SUPPORT (o "!" dos avisos). */
    public static final Color ON_ALERT = token(p -> p.light ? 0xFFFFFF : 0x0B140D);
    public static final Color ERROR_BG = token(p -> p.light ? 0xFDECEA : 0x3A1D18);
    public static final Color ERROR_BORDER = token(p -> p.light ? 0xF0B9B1 : 0x6A2E26);
    public static final Color ERROR_TEXT = token(p -> p.light ? 0x6B1F17 : 0xE7C9C3);
    public static final Color AMBER_BG = token(p -> p.light ? 0xFFF3D1 : 0x3A3214);
    public static final Color AMBER_BORDER = token(p -> p.light ? 0xE9D392 : 0x5A4A1A);
    public static final Color AMBER_TEXT = token(p -> p.light ? 0x6B4A00 : 0xFFE08A);
    // Ilustração do céu (primeira execução e atualização): o céu é o mesmo em todos os temas.
    public static final Color LIME = new Color(0xC8F56A);
    public static final Color TEXT_DARK = new Color(0x0B140D);
    public static final Color SKY_YELLOW = new Color(0xFFC93C);
    public static final Color SKY_SOFT = new Color(0x9AAE9E);

    private static final Font REGULAR = loadFont("Regular");
    private static final Font SEMIBOLD = loadFont("SemiBold");
    private static final Font BOLD = loadFont("Bold");
    private static final Font EXTRABOLD = loadFont("ExtraBold");
    private static final Font DISPLAY = loadFont("Anton-Regular.ttf");

    private Theme() {}

    public static Font regular(float size) { return REGULAR.deriveFont(size); }
    public static Font semibold(float size) { return SEMIBOLD.deriveFont(size); }
    public static Font bold(float size) { return BOLD.deriveFont(size); }
    public static Font extrabold(float size) { return EXTRABOLD.deriveFont(size); }
    /** Anton, só nos títulos grandes da primeira execução. */
    public static Font display(float size) { return DISPLAY.deriveFont(size); }

    /** Nome da arara no seletor ("Arara-azul") e onde ela vive ("Pantanal"). */
    static String name(Macaw m) { return DATA.getProperty(id(m) + ".nome"); }
    static String place(Macaw m) { return DATA.getProperty(id(m) + ".local"); }

    /** "caninde", "azul"...: pasta dos sprites e prefixo em temas.properties. */
    static String id(Macaw m) { return m.name().toLowerCase(Locale.ROOT); }

    static Palette palette(Macaw m, boolean light) {
        String t = id(m) + (light ? "_claro" : "") + ".";
        ToIntFunction<String> c = k -> Integer.parseInt(DATA.getProperty(t + k).substring(1), 16);
        return new Palette(m, light, c.applyAsInt("fundo"), c.applyAsInt("sup"), c.applyAsInt("borda"),
                c.applyAsInt("texto"), c.applyAsInt("suave"), c.applyAsInt("principal"), c.applyAsInt("sobre_principal"),
                c.applyAsInt("contraste"), c.applyAsInt("sobre_contraste"), c.applyAsInt("apoio"), c.applyAsInt("pronto"));
    }

    /** Escolhe o tema sem mexer nas janelas (antes de criá-las). */
    static void use(Macaw m, boolean light) {
        active = palette(m, light);
        Arara.species(m);
    }

    /** Troca o tema com o app aberto: tokens, FlatLaf, estilos e sprites, e repinta todas as janelas. */
    static void apply(Macaw m, boolean light) {
        if (active.macaw == m && active.light == light) return;
        use(m, light);
        install();
        FlatLaf.updateUI();
        for (Window w : Window.getWindows()) {
            restyle(w);
            w.repaint();
        }
    }

    /** Estilo FlatLaf que depende do tema: guardado para ser refeito na troca (os hex do texto não são tokens). */
    static void style(JComponent c, Supplier<String> style) {
        c.putClientProperty(Theme.class, style);
        c.putClientProperty(FlatClientProperties.STYLE, style.get());
    }

    private static void restyle(Component c) {
        if (c instanceof JComponent j && j.getClientProperty(Theme.class) instanceof Supplier<?> s)
            j.putClientProperty(FlatClientProperties.STYLE, s.get());
        if (c instanceof Container box) for (Component child : box.getComponents()) restyle(child);
    }

    /** Cor da paleta com transparência (0 a 255), para fundos de selos e item ativo; acompanha o tema. */
    public static Color alpha(Color c, int alpha) {
        return new Token(p -> (c.getRGB() & 0xFFFFFF) | alpha << 24);
    }

    public static String hex(Color c) {
        String rgb = String.format("#%06X", c.getRGB() & 0xFFFFFF);
        return c.getAlpha() == 255 ? rgb : rgb + String.format("%02X", c.getAlpha());
    }

    public static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.of(
                "@background", hex(BG),
                "@foreground", hex(TEXT),
                "@componentBackground", hex(BG),
                "@accentColor", hex(PRIMARY),
                "@selectionBackground", hex(ACCENT),
                "@selectionForeground", hex(ON_ACCENT),
                "@disabledForeground", hex(TEXT_SOFT)));
        if (active.light) FlatLightLaf.setup();
        else FlatDarkLaf.setup();
        UIManager.put("defaultFont", regular(14));
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.focusedBorderColor", PRIMARY);
        UIManager.put("Component.focusColor", alpha(PRIMARY, 120));
        UIManager.put("Component.arc", 999);
        UIManager.put("TextComponent.arc", 999);
        UIManager.put("Button.arc", 999);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("TitlePane.background", BG);
        UIManager.put("TitlePane.inactiveBackground", BG);
        UIManager.put("TitlePane.showIcon", false);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("Panel.background", BG);
    }

    /** Mistura a com b (t = 0 é a, 1 é b), canal a canal. */
    static int mix(int a, int b, double t) {
        int r = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int ca = a >> shift & 0xFF, cb = b >> shift & 0xFF;
            r |= (int) Math.round(ca + (cb - ca) * t) << shift;
        }
        return r;
    }

    private static boolean canindeDark(Palette p) {
        return p.macaw == Macaw.CANINDE && !p.light;
    }

    private static Color token(ToIntFunction<Palette> rgb) {
        return new Token(p -> 0xFF000000 | rgb.applyAsInt(p));
    }

    /**
     * Cor que lê o tema ativo a cada uso. O Java2D e o Swing pintam com {@link Color#getRGB()}, então um componente
     * com {@code setForeground(Theme.TEXT)} muda de cor sozinho na troca de tema, só com um repaint.
     */
    private static final class Token extends Color {
        private final ToIntFunction<Palette> argb;

        Token(ToIntFunction<Palette> argb) {
            super(0, true);
            this.argb = argb;
        }

        @Override
        public int getRGB() {
            return argb.applyAsInt(active);
        }

        @Override
        public int hashCode() {
            return getRGB();
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = Theme.class.getResourceAsStream("temas.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private static Font loadFont(String weight) {
        String file = weight.endsWith(".ttf") ? weight : "NunitoSans-" + weight + ".ttf";
        try (InputStream in = Theme.class.getResourceAsStream("fonts/" + file)) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font); // para o HTML dos JLabel achar a família
            return font;
        } catch (Exception e) {
            throw new IllegalStateException("Fonte " + file + " ausente", e);
        }
    }
}
