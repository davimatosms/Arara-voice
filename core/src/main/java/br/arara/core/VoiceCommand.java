package br.arara.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Comandos de voz. Só vale quando o trecho curto inteiro é o comando ("Oi, Arara!"), nunca uma frase que contém a
 * palavra ("a arara azul voou"): é isso que separa o comando da conversa. Os apelidos cobrem o que o Whisper leve
 * costuma escrever para cada palavra.
 */
public enum VoiceCommand {
    /** Chama o Arara: começa um ditado. */
    ARARA("arara", "a rara", "ara ra", "oi arara", "ei arara", "ola arara", "ok arara", "hey arara", "e ai arara"),
    /** Termina o ditado chamado por voz. */
    PRONTO("pronto", "pronta", "bronto", "ta pronto", "esta pronto"), // "bronto": o Whisper leve troca o P pelo B
    /** Cola o texto que espera aprovação. */
    APROVADO("aprovado", "aprovada", "aprovo"),
    /** Desiste do texto que espera aprovação (ele fica no histórico). */
    CANCELA("cancela", "cancelar", "cancelado", "cancelada", "cancele");

    private final Set<String> aliases;

    VoiceCommand(String... aliases) {
        this.aliases = Set.of(aliases);
    }

    /** O comando que o texto inteiro é, ou null. Aceita o comando repetido (laço do Whisper em trecho curto). */
    public static VoiceCommand match(String text) {
        if (text == null) return null;
        String plain = normalize(text);
        if (plain.isEmpty()) return null;
        VoiceCommand direct = byAlias(plain);
        if (direct != null) return direct;
        String[] words = plain.split(" ");
        for (int unit = 1; unit <= 3 && unit < words.length; unit++) {
            if (!repeats(words, unit)) continue;
            VoiceCommand c = byAlias(String.join(" ", java.util.Arrays.copyOf(words, unit)));
            if (c != null) return c;
        }
        return null;
    }

    private static VoiceCommand byAlias(String plain) {
        for (VoiceCommand c : values()) if (c.aliases.contains(plain)) return c;
        return null;
    }

    /**
     * O texto é a mesma sequência de {@code unit} palavras repetida; a última palavra pode vir cortada ("Arara. Arar"),
     * porque o laço do Whisper para no fim do contexto.
     */
    private static boolean repeats(String[] words, int unit) {
        int last = words.length - 1;
        for (int i = unit; i < last; i++) if (!words[i].equals(words[i % unit])) return false;
        return words[last].length() > 0 && words[last % unit].startsWith(words[last]);
    }

    /** Minúsculas, sem acento e sem pontuação, com um espaço só entre as palavras. */
    static String normalize(String text) {
        String noMarks = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
