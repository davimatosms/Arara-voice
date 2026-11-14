package br.arara.core;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Teste ponta a ponta com hardware real: microfone padrão, alto-falante (voz do Windows falando PT-BR),
 * Whisper em CPU, atalho global, Notepad. Não roda no CI.
 *
 * <pre>
 * ./gradlew e2e                     # tudo
 * ./gradlew e2e --args="mic"        # 1 e 2: microfone e VAD, grava build/e2e/fala.wav
 * ./gradlew e2e --args="whisper"    # 3: LEVE e PRECISO sobre build/e2e/fala.wav (baixa os modelos)
 * ./gradlew e2e --args="whisper LEVE"  # só um modelo
 * ./gradlew e2e --args="fluxo"      # 4 e 5: AraraCore + atalho + Notepad, Esc, gravação sem fala
 * ./gradlew e2e --args="colar"      # só o ClipboardPaster no Notepad, 5 vezes (diagnóstico)
 * </pre>
 *
 * Precisa de alto-falante ligado (não mudo) perto do microfone padrão e da voz pt-BR do Windows (Maria).
 * Modelos ficam em %LOCALAPPDATA%\Arara-e2e\models para não baixar de novo. Não mexa no teclado durante o "fluxo".
 */
public final class E2E {
    static final String FRASE = "Olá! Eu estou testando o Arara, um aplicativo de ditado por voz em português do Brasil.";
    static final AudioFormat FORMAT = new AudioFormat(Vad.RATE, 16, 1, true, false);
    static final Path OUT = Path.of("build", "e2e");
    static final Path MODELS = Path.of(System.getenv("LOCALAPPDATA"), "Arara-e2e", "models");
    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "tudo";
        Files.createDirectories(OUT);
        Thread watchdog = new Thread(() -> { // nunca pendura: Notepad sem foco, Whisper travado etc.
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(10));
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("\n===== TIMEOUT (10 min) =====");
            report.forEach(System.out::println);
            Runtime.getRuntime().halt(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (what.equals("mic") || what.equals("tudo")) mic();
            if (what.equals("whisper") || what.equals("tudo")) whisper(args.length > 1 ? Settings.Model.valueOf(args[1]) : null);
            if (what.equals("fluxo") || what.equals("tudo")) fluxo();
            if (what.equals("colar")) colar();
        } finally {
            System.out.println("\n===== RELATÓRIO =====");
            report.forEach(System.out::println);
        }
        System.exit(0); // JNativeHook e AWT deixam threads vivas
    }

    // ---------- 1 e 2: microfone e VAD ----------

    static void mic() throws Exception {
        log("== 1/2. Microfone e VAD ==");
        List<short[]> chunks = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        long t0 = System.nanoTime();
        Microphone mic = new Microphone(() -> null);
        mic.start(c -> {
            synchronized (chunks) {
                chunks.add(c);
                times.add(System.nanoTime() - t0);
            }
        });
        Thread.sleep(2500); // piso de ruído
        long speakStart = System.nanoTime() - t0;
        speak(FRASE).waitFor();
        long speakEnd = System.nanoTime() - t0;
        Thread.sleep(4000); // silêncio depois da fala
        mic.stop();

        List<short[]> cs;
        synchronized (chunks) {
            cs = new ArrayList<>(chunks);
        }
        int total = cs.stream().mapToInt(c -> c.length).sum();
        log("blocos: %d, amostras: %d (%.2f s)", cs.size(), total, total / (double) Vad.RATE);
        int maxAbs = cs.stream().flatMapToInt(c -> { int[] a = new int[c.length]; for (int i = 0; i < c.length; i++) a[i] = Math.abs(c[i]); return Arrays.stream(a); }).max().orElse(0);
        log("pico absoluto: %d", maxAbs);

        // dB por bloco, separado em antes / durante / depois da fala (pelo tempo do processo da voz)
        List<Double> before = new ArrayList<>(), during = new ArrayList<>(), after = new ArrayList<>();
        Vad vad = new Vad();
        float minLevel = 1, maxLevel = 0;
        Vad.Result last = null;
        long samples = 0, lastLoud = -1;
        List<String> transitions = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            short[] c = cs.get(i);
            double db = Vad.dbfs(c);
            long t = times.get(i);
            (t < speakStart ? before : t < speakEnd ? during : after).add(db);
            boolean heardBefore = vad.heardSpeech();
            Vad.Result r = vad.feed(c);
            samples += c.length;
            minLevel = Math.min(minLevel, vad.level());
            maxLevel = Math.max(maxLevel, vad.level());
            if (r != last || vad.heardSpeech() != heardBefore) {
                transitions.add(String.format("%.2f s: %s%s", samples / (double) Vad.RATE, r, vad.heardSpeech() ? " (fala ouvida)" : ""));
                last = r;
            }
        }
        transitions.forEach(s -> log("  VAD %s", s));
        log("fala (processo da voz): %.2f s a %.2f s", speakStart / 1e9, speakEnd / 1e9);
        double floor = pct(before, 50), speechMed = pct(during, 50), speechP90 = pct(during, 90);
        log("piso de ruído (mediana antes da fala): %.1f dBFS; p90 %.1f", floor, pct(before, 90));
        log("fala: mediana %.1f dBFS, p90 %.1f, máx %.1f", speechMed, speechP90, pct(during, 100));
        log("depois da fala: mediana %.1f dBFS, p90 %.1f", pct(after, 50), pct(after, 90));
        log("onLevel: min %.2f, máx %.2f", minLevel, maxLevel);

        boolean signal = maxAbs > 0 && maxLevel - minLevel > 0.1;
        result("1. Microfone capta sinal e o nível varia", signal,
                "piso %.1f dBFS, fala p90 %.1f dBFS, onLevel %.2f..%.2f", floor, speechP90, minLevel, maxLevel);
        boolean silenceAtEnd = last == Vad.Result.SILENCE && vad.heardSpeech();
        result("2. VAD: fala = LISTENING, silêncio = SILENCE ~2,5 s depois", silenceAtEnd, String.join("; ", transitions));

        short[] all = new short[total];
        int off = 0;
        for (short[] c : cs) {
            System.arraycopy(c, 0, all, off, c.length);
            off += c.length;
        }
        writeWav(all, OUT.resolve("fala.wav"));
        log("gravação salva em %s", OUT.resolve("fala.wav").toAbsolutePath());
    }

    // ---------- 3: Whisper ----------

    static void whisper(Settings.Model only) throws Exception {
        log("== 3. Whisper ==");
        short[] full = readWav(OUT.resolve("fala.wav"));
        Vad vad = new Vad();
        for (int i = 0; i + 512 <= full.length; i += 512) vad.feed(Arrays.copyOfRange(full, i, i + 512));
        short[] pcm = DefaultDictationEngine.speechOnly(full, full.length, vad); // o mesmo corte que o app faz
        log("áudio: %.2f s; sem o silêncio das pontas: %.2f s", full.length / (double) Vad.RATE, pcm.length / (double) Vad.RATE);
        for (Settings.Model m : new Settings.Model[] {Settings.Model.LEVE, Settings.Model.PRECISO}) {
            if (only != null && m != only) continue;
            WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> m);
            long t = System.nanoTime();
            int[] lastPct = {-1};
            w.prepare(p -> {
                int pc = (int) (p * 100);
                if (pc / 10 != lastPct[0] / 10) System.out.printf("  download %s: %d%%%n", m, pc);
                lastPct[0] = pc;
            });
            log("%s: modelo pronto em %.1f s (inclui download se faltava)", m, (System.nanoTime() - t) / 1e9);
            String text = null;
            double[] secs = new double[2];
            for (int i = 0; i < 2; i++) {
                t = System.nanoTime();
                text = w.transcribe(pcm);
                secs[i] = (System.nanoTime() - t) / 1e9;
            }
            long tf = System.nanoTime();
            String fullText = w.transcribe(full);
            log("%s sem cortar o silêncio (só comparação): %.2f s, \"%s\"", m, (System.nanoTime() - tf) / 1e9, fullText);
            w.close();
            double wer = wer(FRASE, text);
            log("%s: \"%s\"", m, text);
            log("%s: latência %.2f s (1ª) / %.2f s (2ª), WER %.0f%%", m, secs[0], secs[1], wer * 100);
            result("3. Whisper " + m, wer <= 0.25, "latência %.2f s / %.2f s, WER %.0f%%, \"%s\"", secs[0], secs[1], wer * 100, text);
        }
    }

    // ---------- 4 e 5: fluxo completo ----------

    record Event(long ms, String what) {}

    /** Só o ClipboardPaster no Notepad, várias vezes: diagnóstico da colagem e da espera de 300 ms. */
    static void colar() throws Exception {
        Path txt = Files.createTempFile("arara-e2e-", ".txt");
        String file = txt.getFileName().toString();
        Robot robot = new Robot();
        robot.setAutoDelay(30);
        new ProcessBuilder("notepad.exe", txt.toString()).start();
        if (!waitWindow(file, true)) throw new IllegalStateException("Notepad não abriu");
        try {
            Thread.sleep(1000);
            for (int i = 1; i <= 5; i++) {
                String prev = "ANTERIOR-" + i;
                ps("Set-Clipboard -Value '" + prev + "'");
                focus(file);
                new ClipboardPaster().paste("[colado " + i + "]");
                Thread.sleep(700);
                log("%d: clipboard depois \"%s\"", i, ps("Get-Clipboard"));
            }
            save(robot, file);
            log("Notepad: \"%s\"", Files.readString(txt, StandardCharsets.UTF_8));
        } finally {
            focus(file);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_W);
            robot.keyRelease(KeyEvent.VK_W);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            log("Notepad fechado: %s", waitWindow(file, false));
            Files.deleteIfExists(txt);
        }
    }

    static void fluxo() throws Exception {
        log("== 4/5. Fluxo completo ==");
        Path data = Files.createTempDirectory("arara-e2e-");
        Files.createDirectories(data.resolve("models"));
        Path model = MODELS.resolve("ggml-base.bin");
        if (Files.exists(model)) Files.createLink(data.resolve("models/ggml-base.bin"), model);
        Path txt = Files.createTempFile("arara-e2e-", ".txt");
        Files.writeString(txt, "");
        String file = txt.getFileName().toString();
        // clipboard anterior vindo de outro processo, como no uso real (o dono não é esta JVM)
        String previousClip = "ANTERIOR-" + System.nanoTime();
        ps("Set-Clipboard -Value '" + previousClip + "'");

        AraraCore core = AraraCore.start(data);
        boolean notepadOpen = false;
        Robot robot = new Robot();
        robot.setAutoDelay(30);
        try {
            Settings s = core.settings().load();
            core.settings().save(new Settings(s.hotkey(), Settings.Model.LEVE, s.accel(), null,
                    s.startWithWindows(), s.showPopup(), true));
            core.prepareModel(p -> {});
            core.dictionary().add(new Term("aplicativo", "app"));
            log("dataDir %s, atalho %s, termo aplicativo -> app", data, s.hotkey());

            long t0 = System.nanoTime();
            BlockingQueue<Event> events = new LinkedBlockingQueue<>();
            float[] lv = {1, 0};
            core.engine().addListener(new DictationListener() {
                long ms() { return (System.nanoTime() - t0) / 1_000_000; }
                @Override public void onState(DictationState st) { events.add(new Event(ms(), "state:" + st)); }
                @Override public void onLevel(float l) { lv[0] = Math.min(lv[0], l); lv[1] = Math.max(lv[1], l); }
                @Override public void onResult(HistoryEntry e) { events.add(new Event(ms(), "result:" + e.texto())); }
                @Override public void onError(String m) { events.add(new Event(ms(), "error:" + m)); }
            });

            new ProcessBuilder("notepad.exe", txt.toString()).start();
            notepadOpen = waitWindow(file, true);
            if (!notepadOpen) throw new IllegalStateException("Notepad não abriu em 10 s");
            Thread.sleep(1000);
            Path hist = data.resolve("historico.jsonl");

            // 4. atalho -> fala -> silêncio -> atalho -> cola
            boolean hookOk = hotkey(robot, file, core, events, "state:LISTENING");
            log("JNativeHook recebeu o atalho do Robot: %s", hookOk);
            speak(FRASE).waitFor();
            Event silence = waitFor(events, "state:SILENCE", 5000);
            log("SILENCE depois da fala: %s", silence != null ? "sim (" + silence.ms + " ms)" : "não");
            hotkey(robot, file, core, events, "state:TRANSCRIBING");
            Event tr = last(events, "state:TRANSCRIBING");
            Event res = waitFor(events, "result:", 120_000);
            Event end = waitAny(events, 10_000, "state:PASTED", "state:ERROR");
            Thread.sleep(500);
            String clipNow = ps("Get-Clipboard"); // antes de qualquer outra tecla
            save(robot, file);
            String pasted = Files.readString(txt, StandardCharsets.UTF_8);
            List<String> lines = lines(hist);
            log("resultado: %s", res);
            log("fim: %s", end);
            log("Notepad: \"%s\" (caracteres fora do texto colado: %s)", pasted, extra(pasted));
            log("historico.jsonl: %s", lines);
            log("clipboard depois: \"%s\" (esperado \"%s\")", clipNow, previousClip);
            log("onLevel no fluxo: %.2f..%.2f", lv[0], lv[1]);
            long transcribeMs = res != null && tr != null ? res.ms - tr.ms : -1;
            long pasteMs = end != null && res != null ? end.ms - res.ms : -1;
            String text = res == null ? "" : res.what.substring("result:".length());
            boolean dictOk = text.contains("app") && !text.toLowerCase(Locale.ROOT).contains("aplicativo");
            boolean ok = end != null && end.what.equals("state:PASTED") && dictOk
                    && clean(pasted).equals(text) && lines.size() == 1 && lines.get(0).contains(text)
                    && clipNow.equals(previousClip);
            result("4. Fluxo completo (atalho, grava, transcreve, dicionário, histórico, cola, clipboard)", ok,
                    "atalho via Robot: %s; transcrição LEVE %d ms; colar (resultado -> PASTED, inclui 300 ms) %d ms; dicionário %s; "
                            + "colado \"%s\"; clipboard %s; WER %.0f%%",
                    hookOk ? "JNativeHook recebeu" : "NÃO recebeu, usei toggle()", transcribeMs, pasteMs,
                    dictOk ? "trocou" : "NÃO trocou", clean(pasted), clipNow.equals(previousClip) ? "restaurado" : "NÃO restaurado",
                    wer(FRASE.replace("aplicativo", "app"), text) * 100);

            // 5a. Esc cancela
            events.clear();
            seen.clear();
            hotkey(robot, file, core, events, "state:LISTENING");
            Process p = speak("Isto não deve ser colado.");
            Thread.sleep(1500);
            focus(file);
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            boolean escHook = waitFor(events, "state:IDLE", 2000) != null;
            if (!escHook) core.engine().cancel();
            p.waitFor();
            Thread.sleep(1500);
            save(robot, file);
            boolean escOk = core.engine().state() == DictationState.IDLE
                    && clean(Files.readString(txt, StandardCharsets.UTF_8)).equals(clean(pasted))
                    && lines(hist).size() == 1
                    && waitFor(events, "state:TRANSCRIBING", 0) == null;
            result("5a. Esc cancela sem colar nem salvar", escOk, "Esc via Robot %s; estado %s",
                    escHook ? "recebido pelo JNativeHook" : "NÃO recebido, usei cancel()", core.engine().state());

            // 5b. gravação sem fala
            events.clear();
            seen.clear();
            hotkey(robot, file, core, events, "state:LISTENING");
            Thread.sleep(3500);
            hotkey(robot, file, core, events, "state:ERROR");
            Event err = waitFor(events, "error:", 3000);
            Thread.sleep(1000);
            save(robot, file);
            String after = Files.readString(txt, StandardCharsets.UTF_8);
            boolean silentOk = err != null && err.what.equals("error:Não detectei fala.")
                    && clean(after).equals(clean(pasted)) && lines(hist).size() == 1;
            result("5b. Gravação sem fala: ERROR \"Não detectei fala.\" sem colar", silentOk,
                    "evento %s; Notepad \"%s\"; histórico %d linha(s)", err, after, lines(hist).size());
        } finally {
            if (notepadOpen) {
                try { // fecha só a aba do arquivo temporário (o Notepad do Windows 11 pode ter abas do usuário)
                    focus(file);
                    robot.keyPress(KeyEvent.VK_CONTROL);
                    robot.keyPress(KeyEvent.VK_W);
                    robot.keyRelease(KeyEvent.VK_W);
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                    log("Notepad fechado: %s", waitWindow(file, false));
                } catch (Exception e) {
                    log("NÃO fechei o Notepad (%s): feche a janela %s à mão", e.getMessage(), file);
                }
            }
            core.close();
            Files.deleteIfExists(txt);
        }
    }

    /**
     * O atalho Ctrl+Shift+Space também chega ao app em foco (o JNativeHook não engole teclas) e o Notepad do
     * Windows 11 digita um espaço não separável (U+00A0) a cada aperto. Ignora isso ao comparar o texto colado.
     */
    static String clean(String s) {
        return s.replace(' ', ' ').strip();
    }

    static String extra(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().filter(c -> c == 0xA0 || c != ' ' && Character.isWhitespace(c)).forEach(c -> b.append(String.format("U+%04X ", c)));
        return b.toString().strip();
    }

    static List<String> lines(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
    }

    /** Aperta o atalho (Ctrl+Shift+Space) com o Robot; se o JNativeHook não reagir, chama toggle(). */
    static boolean hotkey(Robot robot, String file, AraraCore core, BlockingQueue<Event> events, String expect) throws Exception {
        focus(file);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        if (waitFor(events, expect, 3000) != null) return true;
        core.engine().toggle();
        return false;
    }

    static void save(Robot robot, String file) throws Exception {
        focus(file);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_S);
        robot.keyRelease(KeyEvent.VK_S);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(1000);
    }

    static Event waitFor(BlockingQueue<Event> q, String prefix, long ms) throws InterruptedException {
        return waitAny(q, ms, prefix);
    }

    /** Consome eventos até achar um que comece com algum dos prefixos (os consumidos ficam em seen). */
    static Event waitAny(BlockingQueue<Event> q, long ms, String... prefixes) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        for (Event e : seen) for (String p : prefixes) if (e.what.startsWith(p) && !used.contains(e)) { used.add(e); return e; }
        while (true) {
            long left = deadline - System.currentTimeMillis();
            Event e = left > 0 ? q.poll(left, TimeUnit.MILLISECONDS) : q.poll();
            if (e == null) return null;
            System.out.println("  evento " + e);
            seen.add(e);
            for (String p : prefixes) if (e.what.startsWith(p)) { used.add(e); return e; }
        }
    }

    static final List<Event> seen = new ArrayList<>();
    static final List<Event> used = new ArrayList<>();

    static Event last(BlockingQueue<Event> q, String what) {
        return seen.stream().filter(e -> e.what.equals(what)).reduce((a, b) -> b).orElse(null);
    }

    static final String FOCUS_PS = """
            param($name)
            Add-Type @'
            using System; using System.Runtime.InteropServices; using System.Text;
            public class W {
              [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
              [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
              [DllImport("user32.dll")] public static extern void keybd_event(byte v, byte s, uint f, UIntPtr e);
              [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
            }
            '@
            $p = Get-Process | Where-Object { $_.MainWindowTitle -like "*$name*" } | Select-Object -First 1
            if ($p -and [W]::GetForegroundWindow() -ne $p.MainWindowHandle) {
              # Alt libera o SetForegroundWindow vindo de um processo em segundo plano. Só se o foco estiver em
              # outra janela: Alt no Notepad liga o modo menu e ele engole o Ctrl+V seguinte.
              [W]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [W]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
              [W]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
              [W]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
              Start-Sleep -Milliseconds 300
            }
            $sb = New-Object System.Text.StringBuilder 512
            [W]::GetWindowText([W]::GetForegroundWindow(), $sb, 512) | Out-Null
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            $sb.ToString()
            """;
    static Path focusScript;

    /**
     * Põe a janela do arquivo em primeiro plano e confere. Sem isso o Robot digita na janela que estiver em foco
     * (um Ctrl+W no terminal fecha a aba dele). Lança exceção em vez de mandar tecla para a janela errada.
     */
    static void focus(String file) throws Exception {
        if (focusScript == null) {
            focusScript = Files.createTempFile("arara-e2e-focus-", ".ps1");
            focusScript.toFile().deleteOnExit();
            Files.writeString(focusScript, "﻿" + FOCUS_PS, StandardCharsets.UTF_8);
        }
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                focusScript.toString(), file).redirectErrorStream(true).start();
        String title = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
        if (!title.contains(file)) throw new IllegalStateException("foco não está no Notepad, está em: \"" + title + "\"");
    }

    /** Roda um comando PowerShell e devolve a saída (UTF-8). */
    static String ps(String command) throws Exception {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " + command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }

    /** Espera a janela do Notepad com esse arquivo no título aparecer (ou sumir). */
    static boolean waitWindow(String fileName, boolean present) throws Exception {
        for (int i = 0; i < 20; i++) {
            Process ps = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "@(Get-Process | Where-Object { $_.MainWindowTitle -like '*" + fileName + "*' }).Count").start();
            String out = new String(ps.getInputStream().readAllBytes()).strip();
            ps.waitFor();
            if (out.equals("0") != present) return true;
            Thread.sleep(500);
        }
        return false;
    }

    // ---------- utilidades ----------

    /** Fala o texto com a voz pt-BR do Windows pelo alto-falante padrão. */
    static Process speak(String text) throws Exception {
        String ps = "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'pt-BR' } | Select-Object -First 1; "
                + "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }; $s.Volume = 100; $s.Speak('" + text.replace("'", "''") + "')";
        return new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).inheritIO().start();
    }

    static void writeWav(short[] pcm, Path file) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) b.putShort(s);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(b.array()), FORMAT, pcm.length)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    static short[] readWav(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(file.toString()))) {
            byte[] bytes = in.readAllBytes();
            short[] pcm = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            return pcm;
        }
    }

    static double pct(List<Double> xs, int p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(xs);
        s.sort(Comparator.naturalOrder());
        return s.get(Math.min(s.size() - 1, s.size() * p / 100));
    }

    /** Taxa de erro de palavras, sem pontuação, acento nem maiúsculas. */
    static double wer(String ref, String hyp) {
        String[] r = words(ref), h = words(hyp);
        int[][] d = new int[r.length + 1][h.length + 1];
        for (int i = 0; i <= r.length; i++) d[i][0] = i;
        for (int j = 0; j <= h.length; j++) d[0][j] = j;
        for (int i = 1; i <= r.length; i++)
            for (int j = 1; j <= h.length; j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (r[i - 1].equals(h[j - 1]) ? 0 : 1));
        return (double) d[r.length][h.length] / r.length;
    }

    static String[] words(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return Arrays.stream(n.split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isEmpty()).toArray(String[]::new);
    }

    static void log(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
    }

    static void result(String item, boolean ok, String fmt, Object... args) {
        String line = (ok ? "PASSOU " : "FALHOU ") + item + " | " + String.format(fmt, args);
        System.out.println(line);
        report.add(line);
    }
}
