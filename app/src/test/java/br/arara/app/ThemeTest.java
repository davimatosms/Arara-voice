package br.arara.app;

import br.arara.core.Settings.Macaw;
import com.formdev.flatlaf.FlatLaf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Temas das araras: contraste, troca em tempo de execução, sprites e ícones. */
class ThemeTest {
    @AfterEach
    void backToCaninde() {
        Theme.use(Macaw.CANINDE, false);
    }

    /** Mesmo teste de arte/temas.py: texto e selos com contraste WCAG AA (4,5:1) nos 10 temas. */
    @Test
    void allTenThemesPassWcagAa() {
        List<String> bad = new ArrayList<>();
        for (Macaw m : Macaw.values()) {
            for (boolean light : new boolean[] {false, true}) {
                Theme.Palette p = Theme.palette(m, light);
                String t = Theme.id(m) + (light ? "_claro" : "");
                check(bad, t + " texto/fundo", p.text(), p.bg());
                check(bad, t + " texto/superfície", p.text(), p.surface());
                check(bad, t + " suave/fundo", p.textSoft(), p.bg());
                check(bad, t + " suave/superfície", p.textSoft(), p.surface());
                check(bad, t + " botão principal", p.onPrimary(), p.primary());
                check(bad, t + " selo Ouvindo", p.onAccent(), p.accent());
                check(bad, t + " Transcrevendo", p.support(), p.surface());
                check(bad, t + " Pronto", p.ready(), p.surface());
                if (!light) check(bad, t + " item ativo", p.primary(), p.surface());
            }
        }
        assertTrue(bad.isEmpty(), "abaixo de AA: " + bad);
    }

    @Test
    void decisionsOfTheDesign() {
        // Maracanã: o vermelho da testa é a cor principal nos dois modos; Vermelha: "Pronto" em verde.
        assertEquals(0xFF6B57, Theme.palette(Macaw.MARACANA, false).primary());
        assertEquals(0xCF3A2B, Theme.palette(Macaw.MARACANA, true).primary());
        assertEquals(0x6CCB6E, Theme.palette(Macaw.VERMELHA, false).ready());
        assertEquals("Maracanã", Theme.name(Macaw.MARACANA));
        assertEquals("Caatinga, Bahia", Theme.place(Macaw.LEAR));
    }

    @Test
    void switchingThemeChangesTheTokensEverywhere() {
        JLabel label = new JLabel();
        label.setForeground(Theme.TEXT);
        java.awt.Color soft = Theme.alpha(Theme.PRIMARY, 40);
        assertEquals(0xFF6CC93A, Theme.PRIMARY.getRGB(), "padrão: canindé escura");
        assertEquals(0xFFEEF5EC, label.getForeground().getRGB());

        Theme.use(Macaw.AZUL, true);
        assertEquals(0xFF3550C0, Theme.PRIMARY.getRGB());
        assertEquals(0xFFF1F3FB, Theme.BG.getRGB());
        assertEquals(0xFF141A33, label.getForeground().getRGB(), "o componente já criado acompanha");
        assertEquals(0x283550C0, soft.getRGB(), "a cor com transparência também");
        assertEquals(Theme.READY.getRGB(), Theme.ACTIVE.getRGB(), "no claro, o ativo usa a cor de Pronto");
        assertEquals(0xFF3550C0, Theme.HIGHLIGHT.getRGB(), "o lima é só da canindé escura");

        Theme.use(Macaw.CANINDE, false);
        assertEquals(0xFFC8F56A, Theme.HIGHLIGHT.getRGB());
        assertEquals(0xFF253B20, Theme.SELECTED.getRGB(), "o mesmo verde selecionado de antes dos temas");
    }

    @Test
    void applyReinstallsFlatLafInTheRightMode() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            Theme.apply(Macaw.LEAR, true);
            assertFalse(((FlatLaf) UIManager.getLookAndFeel()).isDark());
            assertEquals(0xFF2F63A8, Theme.PRIMARY.getRGB());
            Theme.apply(Macaw.LEAR, false);
            assertTrue(((FlatLaf) UIManager.getLookAndFeel()).isDark());
            Theme.apply(Macaw.CANINDE, false);
        });
    }

    @Test
    void choosingAMacawLoadsItsSprites() throws Exception {
        BufferedImage caninde = Arara.IMAGE;
        assertSame(Arara.CANINDE, caninde);
        Theme.use(Macaw.VERMELHA, false);
        assertNotSame(caninde, Arara.IMAGE);
        assertPixels(read("araras/vermelha/pousada.png"), Arara.IMAGE);
        assertPixels(read("araras/vermelha/fala_2.png"), Arara.FALA[2]);
        assertPixels(read("araras/vermelha/piscar.png"), Arara.PISCAR);
        assertPixels(read("araras/vermelha/voo_5.png"), Arara.VOO[5]);
        assertSame(Arara.IMAGE, Arara.FALA[0]);
        assertEquals(6, Arara.VOO.length);

        // Um quadro da espécie anterior vira a pousada nova na próxima pintura.
        Arara a = new Arara(1);
        Theme.use(Macaw.MARACANA, false);
        a.setSize(43, 42);
        a.paint(new BufferedImage(43, 42, BufferedImage.TYPE_INT_ARGB).createGraphics());
        assertSame(Arara.IMAGE, a.sprite);
    }

    @Test
    void iconOnlyChangesWithTheSwitchOn() {
        for (Macaw m : Macaw.values()) {
            assertEquals(List.of(Arara.CANINDE), Arara.windowIcons(m, false), "desligada: canindé de sempre");
            assertSame(Arara.CANINDE, Arara.trayIcon(m, false));
        }
        List<Image> on = Arara.windowIcons(Macaw.AZUL, true);
        assertEquals(4, on.size());
        assertEquals(256, on.get(3).getWidth(null));
        assertPixels(read("araras/azul/icone-16.png"), (BufferedImage) on.get(0));
        assertNotSame(Arara.CANINDE, Arara.trayIcon(Macaw.AZUL, true));
    }

    private static void check(List<String> bad, String what, int fg, int bg) {
        if (contrast(fg, bg) < 4.5) bad.add(what + String.format(" %.2f", contrast(fg, bg)));
    }

    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = (rgb >> (16 - 8 * i) & 0xFF) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static BufferedImage read(String name) {
        try {
            return ImageIO.read(Arara.class.getResource(name));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
    }
}
