package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Comandos de voz: o trecho curto inteiro tem que ser o comando, com acento, pontuação e maiúscula à vontade. */
class VoiceCommandTest {
    @Test
    void reconheceOsComandosEApelidos() {
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match("Arara."));
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match(" Oi, Arara! "));
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match("Ei, arará"));
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match("A rara."), "o Whisper às vezes separa");
        assertEquals(VoiceCommand.PRONTO, VoiceCommand.match("Pronto."));
        assertEquals(VoiceCommand.PRONTO, VoiceCommand.match("Bronto"), "o Whisper leve troca o P pelo B; não é palavra do português");
        assertEquals(VoiceCommand.APROVADO, VoiceCommand.match("Aprovado!"));
        assertEquals(VoiceCommand.APROVADO, VoiceCommand.match("aprovada"));
        assertEquals(VoiceCommand.CANCELA, VoiceCommand.match("Cancela."));
        assertEquals(VoiceCommand.CANCELA, VoiceCommand.match("Cancelar"));
    }

    @Test
    void repeticaoDoWhisperContaComoOComando() {
        // Em trecho curto o Whisper às vezes repete a palavra até o fim do contexto: continua sendo só o comando.
        assertEquals(VoiceCommand.APROVADO, VoiceCommand.match("Aprovado. Aprovado. Aprovado. Aprovado."));
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match("Oi Arara. Oi Arara. Oi Arara."));
        assertEquals(VoiceCommand.CANCELA, VoiceCommand.match("Cancela. Cancela."));
        assertEquals(VoiceCommand.ARARA, VoiceCommand.match("Arara. Arara. Arara. Arara. Arara. Arar"), "corte no fim do contexto");
        assertNull(VoiceCommand.match("Pronto, pronto, pronto, obrigado."), "repetição seguida de outra palavra não conta");
        assertNull(VoiceCommand.match("Obrigado. Obrigado. Obrigado."));
    }

    @Test
    void fraseComAPalavraNoMeioNaoEComando() {
        assertNull(VoiceCommand.match("A arara azul voou longe hoje."));
        assertNull(VoiceCommand.match("O orçamento foi aprovado ontem."));
        assertNull(VoiceCommand.match("Pronto para a reunião?"));
        assertNull(VoiceCommand.match("Obrigado."), "alucinação comum do Whisper em silêncio");
        assertNull(VoiceCommand.match(""));
        assertNull(VoiceCommand.match(null));
    }
}
