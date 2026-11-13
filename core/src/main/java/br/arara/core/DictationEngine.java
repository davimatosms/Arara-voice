package br.arara.core;

/** Fluxo do ditado: o atalho chama toggle(), Esc chama cancel(). */
public interface DictationEngine extends AutoCloseable {
    /** IDLE: começa a ouvir. Gravando: para, transcreve, salva no histórico e cola. */
    void toggle();

    /** Para de ouvir sem colar nem salvar. */
    void cancel();

    DictationState state();

    void addListener(DictationListener listener);

    void removeListener(DictationListener listener);

    @Override
    void close();
}
