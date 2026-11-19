package br.arara.app;

import br.arara.app.demo.DemoCore;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Início em janela baixa (notebook com escala de 150%, janela menor que 720 de altura): o fim da tela rola. */
class HomePageTest {
    @Test
    void rolaQuandoAJanelaEBaixa() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de ambiente gráfico (o CI no Windows tem)");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            MainView view = new MainView(core.engine(), core.history(), core.dictionary(), core.settings());
            view.show(0);
            JFrame f = new JFrame();
            f.setUndecorated(true);
            f.setContentPane(view);
            f.addNotify();
            f.setSize(1100, 520);
            f.validate();
            try {
                JScrollPane scroll = find(find(view, HomePage.class), JScrollPane.class);
                assertNotNull(scroll, "a Início precisa de rolagem, senão os cartões de baixo ficam fora da janela");
                int content = scroll.getViewport().getView().getPreferredSize().height;
                int visible = scroll.getViewport().getExtentSize().height;
                assertTrue(content > visible, "nesta altura o conteúdo deveria passar da janela");
                assertTrue(scroll.getVerticalScrollBar().getMaximum() >= content,
                        "a barra de rolagem precisa alcançar o fim do conteúdo");
            } finally {
                f.setContentPane(new JPanel());
                f.dispose();
            }
        });
    }

    private static <T> T find(Component c, Class<T> type) {
        if (c == null) return null;
        if (type.isInstance(c)) return type.cast(c);
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
