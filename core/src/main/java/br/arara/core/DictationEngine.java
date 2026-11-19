package br.arara.core;

/** Fluxo do ditado: o atalho chama toggle(), Esc chama cancel(). */
public interface DictationEngine extends AutoCloseable {
    /** Prazo para dizer "Aprovado" (ou apertar o atalho) no ditado chamado por voz; depois disso não cola. */
    long APPROVAL_TIMEOUT_MS = 15_000;

    /** IDLE: começa a ouvir. Gravando: para, transcreve, salva no histórico e cola. */
    void toggle();

    /** Segurar para falar, tecla apertada: começa a ouvir e só release() encerra (o silêncio não). Ouvindo: ignora. */
    default void press() {
        if (state() != DictationState.LISTENING && state() != DictationState.SILENCE) toggle();
    }

    /** Segurar para falar, tecla solta: encerra como toggle(); toque curto demais cancela sem colar. */
    default void release() {}

    /** Para de ouvir sem colar nem salvar. Esperando aprovação: não cola (o texto fica no histórico). */
    void cancel();

    /** Comando de voz "Arara": começa a ouvir como o atalho, mas termina com "Pronto" ou silêncio e pode pedir aprovação. */
    default void startByVoice() {
        if (state() == DictationState.IDLE) toggle();
    }

    /** Comando de voz "Aprovado": cola o texto que espera aprovação. Fora da espera, não faz nada. */
    default void approve() {}

    DictationState state();

    void addListener(DictationListener listener);

    void removeListener(DictationListener listener);

    @Override
    void close();
}
