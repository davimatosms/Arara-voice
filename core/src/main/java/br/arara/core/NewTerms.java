package br.arara.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Termos dos ditados que ainda não estão no dicionário e têm cara de ser o que o Whisper erra: siglas (PRD), palavras
 * com maiúscula no meio (GitHub), letras com números (GPT4), nomes próprios no meio da frase (Claude) e estrangeirismos
 * com k, w ou y (deploy, workflow). Só regra local, sem lista de palavras nem internet.
 * shortcut: heurística; nome próprio no começo da frase fica de fora (não dá para separar de uma palavra comum).
 */
public final class NewTerms {
    /** Um termo e em quantos ditados ele apareceu. */
    public record Suggestion(String term, int count) {}

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
    /** Estrangeirismos com k, w ou y que já são português do dia a dia. */
    private static final Set<String> COMMON = Set.of("ok", "okay", "show", "kg", "km");

    private NewTerms() {}

    /** Termos de um ditado, na ordem em que aparecem, sem repetir (sem diferenciar maiúsculas). */
    public static List<String> in(String text, Collection<Term> dictionary) {
        Set<String> known = documented(dictionary);
        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String word = m.group();
            String key = word.toLowerCase(Locale.ROOT);
            if (found.containsKey(key) || known.contains(key) || COMMON.contains(key)) continue;
            if (candidate(word, sentenceStart(text, m.start()))) found.put(key, word);
        }
        return new ArrayList<>(found.values());
    }

    /**
     * Termos do histórico inteiro, do que aparece em mais ditados para o que aparece em menos; no empate, o que veio
     * primeiro na lista (o histórico vem do mais novo para o mais antigo).
     */
    public static List<Suggestion> across(List<HistoryEntry> history, Collection<Term> dictionary, int limit) {
        Map<String, String> form = new LinkedHashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (HistoryEntry e : history) {
            for (String term : in(e.texto(), dictionary)) {
                String key = term.toLowerCase(Locale.ROOT);
                form.putIfAbsent(key, term);
                count.merge(key, 1, Integer::sum);
            }
        }
        List<String> keys = new ArrayList<>(count.keySet());
        keys.sort(Comparator.comparingInt((String k) -> count.get(k)).reversed()); // sort estável: mantém o empate
        List<Suggestion> out = new ArrayList<>();
        for (String k : keys.subList(0, Math.min(limit, keys.size()))) out.add(new Suggestion(form.get(k), count.get(k)));
        return out;
    }

    static boolean candidate(String word, boolean sentenceStart) {
        int letters = 0, upper = 0;
        boolean digit = false, upperInside = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isDigit(c)) digit = true;
            if (!Character.isLetter(c)) continue;
            letters++;
            if (Character.isUpperCase(c)) {
                upper++;
                if (i > 0) upperInside = true;
            }
        }
        if (letters == 0 || word.length() < 2) return false;
        if (digit) return true; // GPT4, v2, mp3
        if (letters >= 2 && upper == letters) return true; // sigla: PRD, API
        if (upperInside) return true; // GitHub, iPhone, JavaScript
        if (upper == 1 && Character.isUpperCase(word.charAt(0))) return !sentenceStart; // nome no meio da frase
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.indexOf('k') >= 0 || lower.indexOf('w') >= 0 || lower.indexOf('y') >= 0; // deploy, workflow
    }

    /** Começo do texto, ou só espaços e aspas desde o último . ! ? … : ou quebra de linha. */
    static boolean sentenceStart(String text, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) && c != '\n') continue;
            if (c == '"' || c == '\'' || c == '“' || c == '(' || c == '«') continue;
            return ".!?…:\n".indexOf(c) >= 0;
        }
        return true;
    }

    /** Palavras dos dois lados do dicionário ("ouvir" e "escrever"), em minúsculas. */
    private static Set<String> documented(Collection<Term> dictionary) {
        Set<String> words = new HashSet<>();
        for (Term t : dictionary) {
            for (String side : new String[] {t.ouvir(), t.escrever()}) {
                if (side == null) continue;
                Matcher m = WORD.matcher(side);
                while (m.find()) words.add(m.group().toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }
}
