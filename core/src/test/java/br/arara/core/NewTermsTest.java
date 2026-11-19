package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Termos fora do dicionário: nomes, siglas e termos técnicos que o Whisper costuma errar. */
class NewTermsTest {
    private static final List<Term> NONE = List.of();

    @Test
    void achaSiglasNomesCamelCaseNumerosEEstrangeirismos() {
        assertEquals(List.of("PRD", "GitHub", "GPT4", "Claude", "deploy", "workflow"),
                NewTerms.in("Abre o PRD no GitHub, compara com o GPT4 e pede para o Claude fazer o deploy do workflow.", NONE));
    }

    @Test
    void ignoraPalavrasComunsEMaiusculaDeComecoDeFrase() {
        assertEquals(List.of(), NewTerms.in("Precisamos revisar o contrato antes de sexta. Amanhã a gente remarca! "
                + "Ok? Quem não puder, me avisa por aqui.", NONE));
        assertEquals(List.of(), NewTerms.in("A reunião é às 10 e dura 2 horas.", NONE), "números sozinhos não contam");
        assertEquals(List.of(), NewTerms.in("", NONE));
    }

    @Test
    void nomeNoComecoDaFraseSoContaSeAparecerNoMeioDeOutra() {
        assertEquals(List.of(), NewTerms.in("Vulkan é rápido.", NONE), "no começo da frase não dá para saber");
        assertEquals(List.of("Vulkan"), NewTerms.in("Testei o Vulkan ontem.", NONE));
    }

    @Test
    void naoRepeteEPreservaAPrimeiraForma() {
        assertEquals(List.of("API"), NewTerms.in("A API caiu. Reinicia a API e a api de novo.", NONE));
    }

    @Test
    void ignoraOQueJaEstaNoDicionarioNosDoisLados() {
        List<Term> dict = List.of(new Term("vê esse code", "VS Code"), new Term("claude", "Claude"));
        assertEquals(List.of("PRD"), NewTerms.in("Abre o VS Code e pede para o Claude ler o PRD.", dict));
        assertEquals(List.of(), NewTerms.in("Isso aqui é do vê esse Code.", dict), "palavra do lado \"ouvir\" também conta");
    }

    @Test
    void sugestoesDoHistoricoOrdenadasPorFrequencia() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 19, 10, 0);
        List<HistoryEntry> history = List.of(
                new HistoryEntry(t.plusMinutes(3), "Manda o PDF para o Davi."),
                new HistoryEntry(t.plusMinutes(2), "O PDF do Davi está no GitHub."),
                new HistoryEntry(t.plusMinutes(1), "Revisa o PDF, por favor."),
                new HistoryEntry(t, ""));
        assertEquals(List.of(new NewTerms.Suggestion("PDF", 3), new NewTerms.Suggestion("Davi", 2),
                new NewTerms.Suggestion("GitHub", 1)), NewTerms.across(history, NONE, 10));
        assertEquals(List.of(new NewTerms.Suggestion("PDF", 3)), NewTerms.across(history, NONE, 1), "respeita o limite");
        assertEquals(List.of(new NewTerms.Suggestion("Davi", 2), new NewTerms.Suggestion("GitHub", 1)),
                NewTerms.across(history, List.of(new Term("pê dê efe", "PDF")), 10));
    }
}
