package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Trecho de comando completado com o ruído de fundo do próprio microfone, não com silêncio digital. */
class CommandAudioTest {
    @Test
    void completaAte1Segundo5ComORuidoDoComeco() {
        short[] pcm = new short[Vad.RATE]; // 1 s: 0,1 s de fundo (1, 2, 3...) e depois "fala" (1000)
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (i < Vad.RATE / 10 ? i % 7 + 1 : 1000);
        short[] out = WhisperTranscriber.padWithBackground(pcm);
        assertEquals(WhisperTranscriber.COMMAND_SAMPLES, out.length);
        assertArrayEquals(pcm, Arrays.copyOf(out, pcm.length), "a fala fica no começo, intacta");
        for (int i = pcm.length; i < out.length; i++) {
            assertEquals(pcm[(i - pcm.length) % (Vad.RATE / 10)], out[i], "depois da fala, o fundo repetido");
        }
    }

    @Test
    void trechoLongoFicaComoEsta() {
        short[] pcm = new short[WhisperTranscriber.COMMAND_SAMPLES + 10];
        assertSame(pcm, WhisperTranscriber.padWithBackground(pcm));
    }

    @Test
    void aquecerSoCarregaOModeloSemTranscrever() {
        // Transcrever silêncio para "aquecer" fazia o Whisper repetir por ~5 s, e o primeiro "Arara" esperava na fila.
        int[] loads = {0}, transcribes = {0};
        Transcriber t = new Transcriber() {
            @Override
            public String transcribe(short[] pcm16k) {
                transcribes[0]++;
                return "";
            }

            @Override
            public void load() {
                loads[0]++;
            }
        };
        VoiceCommandRecognizer.whisper(() -> t).warmUp();
        assertEquals(1, loads[0]);
        assertEquals(0, transcribes[0]);
    }

    @Test
    void promptComAraraPorUltimo() {
        // Medido com a voz do Mário (8 trechos x 6 recortes): "Arara Pronto Aprovado Cancela" repetia em 17 de 48;
        // "Aprovado Cancela Pronto Arara", em 1 de 48 (0,37 s em média).
        assertEquals("Aprovado Cancela Pronto Arara", WhisperTranscriber.COMMAND_PROMPT);
    }

    @Test
    void promptSemPontuacao() {
        // Com pontos ("Arara. Pronto.") o Whisper leve continuava a lista e repetia a palavra por até 5 s.
        assertEquals(-1, WhisperTranscriber.COMMAND_PROMPT.indexOf('.'));
        assertEquals(-1, WhisperTranscriber.COMMAND_PROMPT.indexOf(','));
    }
}
