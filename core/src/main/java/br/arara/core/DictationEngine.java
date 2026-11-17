package br.arara.core;

/** Fluxo do ditado: o atalho chama toggle(), Esc chama cancel(). */
public interface DictationEngine extends AutoCloseable {
    /** IDLE: começa a ouvir. Gravando: para, transcreve, salva no histórico e cola. */
    void toggle();

    /** Segurar para falar, tecla apertada: começa a ouvir e só release() encerra (o silêncio não). Ouvindo: ignora. */
    default void press() {
        if (state() != DictationState.LISTENING && state() != DictationState.SILENCE) toggle();
    }

    /** Segurar para falar, tecla solta: encerra como toggle(); toque curto demais cancela sem colar. */
    default void release() {}

    /** Para de ouvir sem colar nem salvar. */
    void cancel();

    DictationState state();

    void addListener(DictationListener listener);

    void removeListener(DictationListener listener);

    @Override
    void close();
}
