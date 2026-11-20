package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoresTest {
    @TempDir
    Path dir;

    @Test
    void jsonQuoteRoundTrip() {
        String tricky = "aspas \" barra \\ linha\nnova\ttab \u0001 ção";
        assertEquals(tricky, Json.parse(Json.quote(tricky)));
        assertEquals(Map.of("a", List.of(1.0, true), "b", "\u00e9"),
                Json.parse(" {\"a\": [1, true], \"b\": \"\\u00e9\"} "));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
    }

    @Test
    void historyAppendsAndListsNewestFirst() throws Exception {
        Path file = dir.resolve("historico.jsonl");
        HistoryStore h = new JsonlHistoryStore(file);
        assertEquals(List.of(), h.all());

        HistoryEntry a = new HistoryEntry(LocalDateTime.of(2026, 11, 13, 14, 32, 0), "primeiro \"texto\"");
        HistoryEntry b = new HistoryEntry(LocalDateTime.of(2026, 11, 13, 14, 33, 5), "segundo\nlinha");
        h.append(a);
        h.append(b);

        assertEquals(List.of(b, a), h.all());
        assertEquals("{\"data\":\"2026-11-13T14:32:00\",\"texto\":\"primeiro \\\"texto\\\"\"}",
                Files.readAllLines(file).get(0));

        h.clear();
        assertEquals(List.of(), h.all());
    }

    @Test
    void historyToleratesTruncatedLastLine() throws Exception {
        Path file = dir.resolve("historico.jsonl");
        HistoryStore h = new JsonlHistoryStore(file);
        HistoryEntry a = new HistoryEntry(LocalDateTime.of(2026, 1, 1, 10, 0, 0), "ok");
        h.append(a);
        Files.writeString(file, "{\"data\":\"2026-01-01T10:00:01\",\"tex", StandardOpenOption.APPEND);
        assertEquals(List.of(a), h.all());

        HistoryEntry b = new HistoryEntry(LocalDateTime.of(2026, 1, 1, 10, 0, 2), "depois");
        h.append(b);
        assertEquals(List.of(b, a), h.all());
    }

    @Test
    void dictionaryReplacesWholeWordsIgnoringCase() {
        Path file = dir.resolve("dicionario.json");
        DictionaryStore d = new JsonDictionaryStore(file);
        d.add(new Term("davi", "Davi"));
        d.add(new Term("visual studio", "Visual Studio"));
        d.add(new Term("visual studio code", "VS Code"));
        d.add(new Term("ação", "Ação$1"));

        assertEquals("Davi abriu o VS Code e o Visual Studio, mas não o davidson. Ação$1!",
                d.apply("DAVI abriu o visual studio code e o Visual studio, mas não o davidson. ação!"));

        DictionaryStore reloaded = new JsonDictionaryStore(file);
        assertEquals(d.all(), reloaded.all());

        reloaded.add(new Term("DAVI", "Davi M."));
        reloaded.remove(new Term("ação", "Ação$1"));
        assertEquals(List.of(new Term("visual studio", "Visual Studio"),
                new Term("visual studio code", "VS Code"), new Term("DAVI", "Davi M.")),
                new JsonDictionaryStore(file).all());
    }

    @Test
    void settingsDefaultsAndRoundTrip() throws Exception {
        Path file = dir.resolve("config.json");
        assertEquals(Settings.defaults(), new JsonSettingsStore(file).load());

        Settings s = new Settings("Mouse4", Settings.Model.LEVE, Settings.Accel.VULKAN, "Microfone \"USB\"",
                false, true, false, false, "0.3.0", Settings.Macaw.LEAR, true, true, true, false, Settings.Sound.PIXEL, 80, Settings.SoundLength.LONGA,
                true, false);
        new JsonSettingsStore(file).save(s);
        assertEquals(s, new JsonSettingsStore(file).load());

        Files.writeString(file, "{\"hotkey\":\"Ctrl+Shift+K\",\"model\":\"INEXISTENTE\"}");
        Settings partial = new JsonSettingsStore(file).load();
        assertEquals("Ctrl+Shift+K", partial.hotkey());
        assertEquals(Settings.defaults().model(), partial.model());
        assertTrue(partial.checkUpdates(), "config.json antigo, sem o campo: verificar atualizações ligado");

        Files.writeString(file, "lixo");
        assertEquals(Settings.defaults(), new JsonSettingsStore(file).load());
    }

    @Test
    void oldConfigWithoutSoundFieldsKeepsDefaults() throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"hotkey\":\"Ctrl+Shift+Space\",\"macaw\":\"LEAR\"}");
        Settings s = new JsonSettingsStore(file).load();
        assertTrue(s.soundOn());
        assertEquals(Settings.Sound.ARARA, s.sound());
        assertEquals(50, s.soundVolume());
        assertEquals(Settings.SoundLength.NORMAL, s.soundLength());
    }

    @Test
    void oldConfigWithoutVoiceFieldsKeepsWakeOffAndApprovalOn() throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"hotkey\":\"Ctrl+Shift+Space\"}");
        Settings s = new JsonSettingsStore(file).load();
        assertFalse(s.voiceWake(), "chamar por voz deixa o microfone aberto: só ligado de propósito");
        assertTrue(s.voiceApproval());

        Settings on = Settings.defaults().withVoiceWake(true).withVoiceApproval(false);
        new JsonSettingsStore(file).save(on);
        Settings back = new JsonSettingsStore(file).load();
        assertTrue(back.voiceWake());
        assertFalse(back.voiceApproval());
        assertTrue(back.withHotkey("Mouse4").voiceWake(), "os outros with mantêm a voz");
    }

    @Test
    void oldConfigWithoutThemeFieldsKeepsCanindeDarkAndFixedIcon() throws Exception {
        Path file = dir.resolve("config.json");
        // config.json da 0.3.0, de antes dos temas
        Files.writeString(file, "{\"hotkey\":\"Ctrl+Shift+Space\",\"model\":\"LEVE\",\"accel\":\"CPU\",\"microphone\":null,"
                + "\"startWithWindows\":false,\"showPopup\":true,\"escCancels\":true,\"checkUpdates\":true,\"skippedVersion\":null}");
        Settings old = new JsonSettingsStore(file).load();
        assertEquals(Settings.Model.LEVE, old.model());
        assertEquals(Settings.Macaw.CANINDE, old.macaw());
        assertFalse(old.lightMode());
        assertFalse(old.macawIcon());

        Files.writeString(file, "{\"macaw\":\"PAPAGAIO\",\"lightMode\":\"sim\",\"macawIcon\":1}");
        Settings bad = new JsonSettingsStore(file).load();
        assertEquals(Settings.Macaw.CANINDE, bad.macaw());
        assertFalse(bad.lightMode());
        assertFalse(bad.macawIcon());

        new JsonSettingsStore(file).save(old.withMacaw(Settings.Macaw.MARACANA).withLightMode(true));
        Settings back = new JsonSettingsStore(file).load();
        assertEquals(Settings.Macaw.MARACANA, back.macaw());
        assertTrue(back.lightMode());
        assertFalse(back.startWithWindows(), "os campos antigos continuam");
    }
}
