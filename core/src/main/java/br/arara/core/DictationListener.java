package br.arara.core;

/** Eventos do motor de ditado. Podem chegar em qualquer thread: a UI deve repassar para a EDT. */
public interface DictationListener {
    default void onState(DictationState state) {}

    /** Nível do microfone de 0.0 a 1.0 (RMS normalizado), umas 30 vezes por segundo enquanto grava. */
    default void onLevel(float level) {}

    /**
     * Texto do ditado até agora, enquanto a pessoa fala: chega a cada trecho transcrito, sempre o texto inteiro
     * (não só o trecho novo), ainda sem o dicionário. Vem da thread de transcrição.
     */
    default void onPartialText(String textSoFar) {}

    /**
     * Texto ao vivo para o pop-up: o confirmado (o mesmo do {@link #onPartialText}) e, depois dele, a prévia provisória
     * do que foi dito e ainda não voltou do modelo escolhido, feita pelo modelo leve (pode ser vazia). A prévia é só
     * para mostrar (tom mais fraco) e nunca entra no texto colado. Cada chamada substitui a anterior; chega a cada
     * prévia e a cada trecho confirmado. Depois do Esc não chega mais nada; depois da parada, só as confirmações que
     * faltam. Vem da thread da prévia ou da de transcrição.
     */
    default void onLiveText(String confirmed, String provisional) {}

    /**
     * Depois da parada, a cada ~0,5 s: segundos que faltam para a transcrição acabar (áudio pendente x velocidade do
     * modelo neste ditado). 0: não mostrar (falta pouco ou ainda não dá para estimar).
     */
    default void onPending(double seconds) {}

    /** Depois de colar: o modelo ficou atrás da fala neste ditado (fila de mais de 10 s ou mais lento que a fala). */
    default void onSlowModel() {}

    /** Transcrição pronta e já salva no histórico. */
    default void onResult(HistoryEntry entry) {}

    /** Mensagem curta em PT-BR para mostrar ao usuário. */
    default void onError(String message) {}
}
