package br.arara.core;

/** Converte áudio em texto (o Whisper, na versão real). */
@FunctionalInterface
public interface Transcriber {
    /** @param pcm16k áudio mono, 16 kHz, PCM 16-bit */
    String transcribe(short[] pcm16k);

    /** @param previous texto já transcrito do mesmo ditado, para dar continuidade (pode ser vazio) */
    default String transcribe(short[] pcm16k, String previous) {
        return transcribe(pcm16k);
    }
}
