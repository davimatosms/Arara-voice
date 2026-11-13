package br.arara.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Uma linha JSON por transcrição; só acrescenta no fim. */
final class JsonlHistoryStore implements HistoryStore {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final Path file;

    JsonlHistoryStore(Path file) {
        this.file = file;
    }

    @Override
    public synchronized void append(HistoryEntry entry) {
        String line = "{\"data\":" + Json.quote(DATA.format(entry.data()))
                + ",\"texto\":" + Json.quote(entry.texto()) + "}\n";
        try {
            // Um travamento pode ter deixado a última linha sem \n; sem isso a nova entrada grudaria nela.
            if (!endsWithNewline()) line = "\n" + line;
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean endsWithNewline() throws IOException {
        if (!Files.exists(file)) return true;
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            if (f.length() == 0) return true;
            f.seek(f.length() - 1);
            return f.read() == '\n';
        }
    }

    @Override
    public synchronized List<HistoryEntry> all() {
        if (!Files.exists(file)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<HistoryEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                Map<?, ?> m = (Map<?, ?>) Json.parse(line);
                entries.add(new HistoryEntry(LocalDateTime.parse((String) m.get("data")), (String) m.get("texto")));
            } catch (RuntimeException e) {
                // Linha corrompida (ex.: travamento no meio da gravação): pula e mantém o resto.
            }
        }
        Collections.reverse(entries);
        return entries;
    }

    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
