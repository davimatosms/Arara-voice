package br.arara.core;

/** Eventos do motor de ditado. Podem chegar em qualquer thread: a UI deve repassar para a EDT. */
public interface DictationListener {
    default void onState(DictationState state) {}

    /** Nível do microfone de 0.0 a 1.0 (RMS normalizado), umas 30 vezes por segundo enquanto grava. */
    default void onLevel(float level) {}

    /** Transcrição pronta e já salva no histórico. */
    default void onResult(HistoryEntry entry) {}

    /** Mensagem curta em PT-BR para mostrar ao usuário. */
    default void onError(String message) {}
}
