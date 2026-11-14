package br.arara.app;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.Map;

/** Paleta Canindé (DECISOES.md), fontes Nunito Sans e Anton e configuração do FlatLaf. Único lugar com cores. */
public final class Theme {
    public static final Color GREEN = new Color(0x6CC93A);
    public static final Color GREEN_LIGHT = new Color(0x9BE36A);
    public static final Color BLUE = new Color(0x1C72CF);
    public static final Color YELLOW = new Color(0xFFC93C);
    public static final Color BG = new Color(0x0F1712);
    public static final Color SURFACE = new Color(0x18221B);
    public static final Color BORDER = new Color(0x2A3A2E);
    public static final Color TEXT = new Color(0xEEF5EC);
    public static final Color TEXT_SOFT = new Color(0x9AAE9E);
    public static final Color TEXT_DARK = new Color(0x0B140D);
    // Primeira execução (céu da landing v2 e estados de erro).
    public static final Color LIME = new Color(0xC8F56A);
    public static final Color SELECTED = new Color(0x253B20);
    public static final Color ERROR = new Color(0xF0705A);
    public static final Color ERROR_BG = new Color(0x3A1D18);
    public static final Color ERROR_BORDER = new Color(0x6A2E26);
    public static final Color ERROR_TEXT = new Color(0xE7C9C3);
    public static final Color AMBER_BG = new Color(0x3A3214);
    public static final Color AMBER_BORDER = new Color(0x5A4A1A);
    public static final Color AMBER_TEXT = new Color(0xFFE08A);

    private static final Font REGULAR = load("Regular");
    private static final Font SEMIBOLD = load("SemiBold");
    private static final Font BOLD = load("Bold");
    private static final Font EXTRABOLD = load("ExtraBold");
    private static final Font DISPLAY = load("Anton-Regular.ttf");

    private Theme() {}

    public static Font regular(float size) { return REGULAR.deriveFont(size); }
    public static Font semibold(float size) { return SEMIBOLD.deriveFont(size); }
    public static Font bold(float size) { return BOLD.deriveFont(size); }
    public static Font extrabold(float size) { return EXTRABOLD.deriveFont(size); }
    /** Anton, só nos títulos grandes da primeira execução. */
    public static Font display(float size) { return DISPLAY.deriveFont(size); }

    /** Cor da paleta com transparência (0 a 255), para fundos de selos e item ativo. */
    public static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
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
                "@accentColor", hex(GREEN),
                "@selectionBackground", hex(BLUE),
                "@selectionForeground", hex(TEXT),
                "@disabledForeground", hex(TEXT_SOFT)));
        FlatDarkLaf.setup();
        UIManager.put("defaultFont", regular(14));
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.focusedBorderColor", GREEN);
        UIManager.put("Component.focusColor", alpha(GREEN, 120));
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

    private static Font load(String weight) {
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
