package br.arara.core;

import java.util.List;

/** Histórico em %APPDATA%\Arara\historico.jsonl, sem limite de itens. */
public interface HistoryStore {
    /** Acrescenta no fim do arquivo, sem reescrever o resto. */
    void append(HistoryEntry entry);

    /** Todas as entradas, da mais nova para a mais antiga. */
    List<HistoryEntry> all();

    /** Apaga o histórico inteiro. */
    void clear();
}
