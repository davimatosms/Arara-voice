package br.arara.core;

import java.util.List;

/** Dicionário do usuário em %APPDATA%\Arara\dicionario.json. */
public interface DictionaryStore {
    List<Term> all();

    void add(Term term);

    void remove(Term term);

    /** Aplica os termos ao texto transcrito. */
    String apply(String text);
}
