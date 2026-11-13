package br.arara.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mínimo feito à mão: objetos viram Map, arrays viram List, números viram Double. */
final class Json {
    private Json() {}

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** @throws IllegalArgumentException se o texto não for JSON válido */
    static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw p.error();
        return v;
    }

    /** Grava num .tmp e troca de uma vez, para um travamento não deixar o arquivo pela metade. */
    static void writeAtomic(Path file, String content) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw error();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        Map<String, Object> object() {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (accept('}')) return m;
            do {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
            } while (accept(','));
            expect('}');
            return m;
        }

        List<Object> array() {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (accept(']')) return l;
            do {
                l.add(value());
                ws();
            } while (accept(','));
            expect(']');
            return l;
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error();
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw error();
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error();
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error();
                        }
                        i += 4;
                    }
                    default -> throw error();
                }
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean accept(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        void expect(char c) {
            if (!accept(c)) throw error();
        }

        IllegalArgumentException error() {
            return new IllegalArgumentException("JSON inválido na posição " + i);
        }
    }
}
