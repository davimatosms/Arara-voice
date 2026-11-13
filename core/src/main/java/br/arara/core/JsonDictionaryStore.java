package br.arara.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Dicionário em JSON: [{"ouvir":"...","escrever":"..."}]. */
final class JsonDictionaryStore implements DictionaryStore {
    private final Path file;
    private final List<Term> terms = new ArrayList<>();

    JsonDictionaryStore(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            for (Object o : (List<?>) Json.parse(Files.readString(file))) {
                Map<?, ?> m = (Map<?, ?>) o;
                terms.add(new Term((String) m.get("ouvir"), (String) m.get("escrever")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<Term> all() {
        return List.copyOf(terms);
    }

    /** Um termo com o mesmo `ouvir` (sem diferenciar maiúsculas) é substituído. */
    @Override
    public synchronized void add(Term term) {
        if (term.ouvir() == null || term.ouvir().isBlank() || term.escrever() == null) {
            throw new IllegalArgumentException("Termo vazio");
        }
        terms.removeIf(t -> t.ouvir().equalsIgnoreCase(term.ouvir()));
        terms.add(term);
        save();
    }

    @Override
    public synchronized void remove(Term term) {
        if (terms.remove(term)) save();
    }

    /** Troca só palavras inteiras, numa passada só (a saída de um termo não é reprocessada). */
    @Override
    public synchronized String apply(String text) {
        if (terms.isEmpty()) return text;
        Map<String, String> byKey = new HashMap<>();
        for (Term t : terms) byKey.put(t.ouvir().toLowerCase(Locale.ROOT), t.escrever());
        // Termos mais longos primeiro, para "visual studio code" ganhar de "visual studio".
        String alternatives = terms.stream()
                .map(Term::ouvir)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                byKey.getOrDefault(m.group().toLowerCase(Locale.ROOT), m.group())));
    }

    private void save() {
        String json = terms.stream()
                .map(t -> "{\"ouvir\":" + Json.quote(t.ouvir()) + ",\"escrever\":" + Json.quote(t.escrever()) + "}")
                .collect(Collectors.joining(",\n  ", "[\n  ", "\n]\n"));
        Json.writeAtomic(file, terms.isEmpty() ? "[]\n" : json);
    }
}
