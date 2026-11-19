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
                return t == null ? null : VoiceCommand.match(t.transcribeCommand(pcm));
            }

            @Override
            public void warmUp() {
                Transcriber t = light.get();
                if (t != null) t.transcribe(new short[Vad.RATE / 2]); // a prévia carrega (e baixa, se faltar) o modelo leve
            }
        };
    }
}
