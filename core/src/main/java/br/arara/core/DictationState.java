package br.arara.core;

/** Estados do ditado, na ordem em que acontecem. */
public enum DictationState {
    /** Parado, esperando o atalho. */
    IDLE,
    /** Gravando e há fala. */
    LISTENING,
    /** Gravando, mas sem fala há alguns segundos (a arara mostra o "?"). */
    SILENCE,
    /** Gravação parada, Whisper trabalhando. */
    TRANSCRIBING,
    /** Texto salvo no histórico e colado. */
    PASTED,
    /** Algo falhou; o texto, se houver, já está no histórico. */
    ERROR
}
