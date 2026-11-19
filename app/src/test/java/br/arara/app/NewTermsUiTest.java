package br.arara.app;

import br.arara.app.demo.DemoCore;
import br.arara.core.HistoryEntry;
import br.arara.core.Term;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Termos fora do dicionário no Histórico (por ditado) e no Dicionário (sugestões do histórico inteiro). */
class NewTermsUiTest {
    @Test
    void historicoMostraOsTermosDoDitadoEOCliqueVaiParaODicionario() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            core.history().append(new HistoryEntry(LocalDateTime.now(), "Abre o PRD no GitHub e manda para o Davi."));
            List<String> clicked = new ArrayList<>();
            HistoryPage page = new HistoryPage(core.history(), core.dictionary(), () -> {}, clicked::add);
            page.refresh();
            assertEquals(List.of("PRD", "GitHub", "Davi"), page.newTerms.terms(), "o ditado mais novo vem selecionado");
            assertTrue(page.newTerms.isVisible());
            page.newTerms.click("GitHub");
            assertEquals(List.of("GitHub"), clicked);
        });
    }

    @Test
    void semTermosNovosAFaixaSome() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            core.history().append(new HistoryEntry(LocalDateTime.now(), "Lembrar de comprar pão de queijo."));
            HistoryPage page = new HistoryPage(core.history(), core.dictionary(), () -> {}, t -> {});
            page.refresh();
            assertFalse(page.newTerms.isVisible());
        });
    }

    @Test
    void dicionarioSugereOsTermosDoHistoricoEPreencheAoClicar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            DemoCore core = new DemoCore();
            core.history().append(new HistoryEntry(LocalDateTime.now(), "O PRD do Davi."));
            core.history().append(new HistoryEntry(LocalDateTime.now(), "Revisa o PRD."));
            DictionaryPage page = new DictionaryPage(core.dictionary(), core.history(), () -> {});
            page.refresh();
            assertEquals("PRD", page.suggestions.terms().get(0), "o que mais aparece vem primeiro");
            assertTrue(page.suggestions.label("PRD").contains("2"), "mostra quantas vezes apareceu");

            page.suggestions.click("PRD");
            assertEquals("PRD", page.hearText());
            assertEquals("PRD", page.writeText());

            core.dictionary().add(new Term("pê erre dê", "PRD"));
            page.refresh();
            assertFalse(page.suggestions.terms().contains("PRD"), "depois de documentado, sai das sugestões");
        });
    }
}
