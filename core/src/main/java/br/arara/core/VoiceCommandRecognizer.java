package br.arara.core;

import java.util.function.Supplier;

/** Ouve um trecho curto de áudio e diz qual comando de voz ele é (ou null). Pode demorar: chame fora da EDT. */
@FunctionalInterface
public interface VoiceCommandRecognizer {
    /** @param pcm16k trecho curto (até ~2 s), mono, 16 kHz, PCM 16-bit */
    VoiceCommand recognize(short[] pcm16k);

    /** Carrega o que for preciso antes do primeiro comando (o modelo leve). Bloqueia; o padrão não faz nada. */
    default void warmUp() {}

    /**
     * Com o Whisper: o modelo leve transcreve o trecho e {@link VoiceCommand#match} decide. O transcritor vem de um
     * Supplier porque muda com o modelo escolhido (com o Leve escolhido, é o próprio transcritor principal).
     * Transcritor ainda carregando devolve null: o comando se perde e a pessoa repete.
     */
    static VoiceCommandRecognizer whisper(Supplier<Transcriber> light) {
        return new VoiceCommandRecognizer() {
            @Override
            public VoiceCommand recognize(short[] pcm) {
                Transcriber t = light.get();
                if (t == null) return null;
                long start = System.nanoTime();
                String text = t.transcribeCommand(pcm);
                java.util.logging.Logger.getLogger("br.arara.voz").fine(() -> String.format(
                        "Whisper (%.2f s de áudio, %.2f s): \"%s\"", pcm.length / 16000.0, (System.nanoTime() - start) / 1e9, text));
                return VoiceCommand.match(text);
            }

            @Override
            public void warmUp() {
                Transcriber t = light.get();
                if (t != null) t.load(); // só carrega: transcrever silêncio fazia o Whisper repetir por segundos
            }
        };
    }
}
