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
 * ./gradlew e2e --args="voz"        # comandos de voz com o modelo LEVE: acertos, falsos positivos e latência
 * ./gradlew e2e --args="ouvir 45 [mixer]"  # diagnóstico com a voz da pessoa: bipe, 45 s ouvindo comandos no microfone
 *                                   # (padrão do sistema ou o mixer dado); mostra o que o Whisper escreveu em cada
 *                                   # trecho e salva os trechos em build/e2e/comandos/ (só neste computador)
 * ./gradlew e2e --args="longo"      # ditado de ~30 s: tudo no fim (antes) contra incremental (depois), LEVE e PRECISO
 * ./gradlew e2e --args="latencia [MODELO|-] [respira|corrido|-] [s] [previa]"  # ditado de ~90 s em WAV sintético,
 *                                   # parada s segundos depois da última palavra (padrão 0,5): tempos por trecho;
 *                                   # "previa" liga a prévia com o modelo leve e mede quando cada fala aparece
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
                Thread.sleep(TimeUnit.MINUTES.toMillis(what.equals("latencia") ? 60 : 10));
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
            if (what.equals("voz")) voz();
            if (what.equals("fluxo-voz")) fluxoVoz();
            if (what.equals("bancada")) bancada();
            if (what.equals("ouvir")) ouvir(args.length > 1 ? Integer.parseInt(args[1]) : 45,
                    args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null);
            if (what.equals("colar-linux")) colarLinux();
            if (what.equals("longo")) longo(args.length > 1 ? Settings.Model.valueOf(args[1]) : null);
            if (what.equals("bench")) bench(args.length > 1 ? Settings.Model.valueOf(args[1]) : null);
            if (what.equals("latencia")) latencia(args.length > 1 && !args[1].equals("-") ? Settings.Model.valueOf(args[1]) : null,
                    args.length > 2 && !args[2].equals("-") ? args[2] : null, args.length > 3 ? Double.parseDouble(args[3]) : 0.5,
                    args.length > 4 && args[4].equals("previa"));
        } finally {
            System.out.println("\n===== RELATÓRIO =====");
            report.forEach(System.out::println);
        }
        System.exit(0); // JNativeHook e AWT deixam threads vivas
    }

    /** Diagnóstico do colar no Linux/Wayland: cole a saída no relatório do beta. Deixe um editor de texto focado. */
    static void colarLinux() throws Exception {
        AraraCore.diagnosticoColar(E2E::log);
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
            w.prepare((done, total) -> {
                int pc = total > 0 ? (int) (done * 100 / total) : 0;
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

    // ---------- ditado longo: antes x depois ----------

    static final String LONGO = "Bom dia, pessoal. Hoje eu quero contar como foi a reunião de planejamento da semana passada. "
            + "Nós decidimos lançar a nova versão do aplicativo no começo do mês que vem, depois de corrigir os erros mais graves. "
            + "A equipe de design vai entregar as telas novas até sexta-feira, e o time de testes começa na segunda. "
            + "Obrigado a todos pela ajuda.";

    /**
     * Grava o ditado longo uma vez (voz do Windows pelo alto-falante, microfone real) e toca a gravação no engine em
     * tempo real, para os dois fluxos receberem o mesmo áudio. Latência = da parada (fim do áudio) até o texto final.
     * Não usa o atalho global (o Arara do usuário pode estar aberto com ele) nem cola: chama o engine direto.
     */
    static void longo(Settings.Model only) throws Exception {
        log("== Ditado longo ==");
        Path wav = OUT.resolve("longo.wav");
        if (!Files.exists(wav)) {
            List<short[]> chunks = new ArrayList<>();
            Microphone mic = new Microphone(() -> null);
            try {
                mic.start(c -> { synchronized (chunks) { chunks.add(c); } });
            } catch (Exception e) {
                result("Ditado longo", false, "microfone ocupado ou indisponível: %s", e);
                return;
            }
            Thread.sleep(2500);
            long t = System.nanoTime();
            speak(LONGO).waitFor();
            log("voz falou por %.1f s", (System.nanoTime() - t) / 1e9);
            Thread.sleep(1000); // o usuário aperta o atalho ~1 s depois de terminar
            mic.stop();
            synchronized (chunks) {
                writeWav(join(chunks), wav);
            }
        }
        short[] full = readWav(wav);
        Vad v = new Vad();
        for (int i = 0; i + 512 <= full.length; i += 512) v.feed(Arrays.copyOfRange(full, i, i + 512));
        double speech = (v.speechEnd() - v.speechStart()) / (double) Vad.RATE;
        log("gravação %s: %.1f s, fala %.1f s, %d palavras", wav, full.length / (double) Vad.RATE, speech, words(LONGO).length);

        for (Settings.Model m : new Settings.Model[] {Settings.Model.LEVE, Settings.Model.PRECISO}) {
            if (only != null && m != only) continue;
            WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> m);
            w.prepare((done, total) -> {});
            w.transcribe(Arrays.copyOf(full, Vad.RATE * 2)); // aquece

            // antes: o fluxo antigo, tudo no Whisper depois da parada
            long t = System.nanoTime();
            String before = w.transcribe(DefaultDictationEngine.speechOnly(full, full.length, v));
            double beforeS = (System.nanoTime() - t) / 1e9;

            // depois: engine incremental recebendo a gravação em tempo real
            long t0 = System.nanoTime();
            List<String> parts = new ArrayList<>();
            Transcriber logged = new Transcriber() {
                @Override public String transcribe(short[] pcm) { return transcribe(pcm, ""); }
                @Override public String transcribe(short[] pcm, String previous) {
                    long s = System.nanoTime();
                    String r = w.transcribe(pcm, previous);
                    parts.add(String.format("[%.1f s: trecho %.1f s em %.2f s] %s", (s - t0) / 1e9,
                            pcm.length / (double) Vad.RATE, (System.nanoTime() - s) / 1e9, r));
                    return r;
                }
            };
            Path data = Files.createTempDirectory("arara-e2e-longo-");
            BlockingQueue<String> results = new LinkedBlockingQueue<>();
            DefaultDictationEngine engine = new DefaultDictationEngine(new JsonlHistoryStore(data.resolve("h.jsonl")),
                    new JsonDictionaryStore(data.resolve("d.json")), logged, new Replay(full), text -> results.add(text));
            engine.addListener(new DictationListener() {
                @Override public void onError(String msg) { results.add("ERRO: " + msg); }
            });
            engine.toggle();
            Replay.done.await();
            long stop = System.nanoTime();
            engine.toggle();
            String after = String.valueOf(results.poll(5, TimeUnit.MINUTES));
            double afterS = (System.nanoTime() - stop) / 1e9;
            engine.close();
            w.close();
            parts.forEach(p -> log("  %s", p));
            double werB = wer(LONGO, before), werA = wer(LONGO, after);
            log("%s antes: %.2f s, WER %.0f%%, \"%s\"", m, beforeS, werB * 100, before);
            log("%s depois: %.2f s, WER %.0f%%, \"%s\"", m, afterS, werA * 100, after);
            result("Ditado longo " + m, afterS < beforeS && werA <= werB + 0.05,
                    "fala %.1f s; antes %.2f s (WER %.0f%%); depois %.2f s (WER %.0f%%); %d trechos",
                    speech, beforeS, werB * 100, afterS, werA * 100, parts.size());
        }
    }

    // ---------- latência de ditado longo, por trecho ----------

    static final String LONGA = "Bom dia, pessoal. Eu queria aproveitar esses minutos para contar como foi a semana, porque "
            + "aconteceu bastante coisa e nem todo mundo conseguiu acompanhar as reuniões. Na segunda-feira, a gente "
            + "fechou o planejamento do trimestre com a diretoria, e ficou decidido que a prioridade número um é a "
            + "estabilidade do aplicativo, antes de qualquer funcionalidade nova. Isso quer dizer que os erros mais "
            + "graves que os clientes relataram nas últimas semanas vão ser corrigidos primeiro, principalmente o "
            + "problema de sincronização que apaga os dados de quem fica sem internet. Na terça, o time de design "
            + "apresentou as telas novas do cadastro, e o retorno foi muito bom, mas ainda falta ajustar o contraste "
            + "das cores e o tamanho dos botões para quem usa o celular com uma mão só. Na quarta e na quinta, eu "
            + "passei a maior parte do tempo conversando com o pessoal do suporte, para entender quais são as "
            + "reclamações mais comuns, e montei uma lista com os vinte casos que mais se repetem. Essa lista vai "
            + "estar na pasta compartilhada até amanhã de manhã. Para a próxima semana, eu peço que cada equipe "
            + "escolha três itens dessa lista e traga uma estimativa de prazo na reunião de quarta-feira. Se alguém "
            + "tiver dúvida, pode me chamar a qualquer hora. Obrigado a todos e um ótimo fim de semana.";

    /**
     * Ditado de ~60-80 s gerado pela voz pt-BR direto em WAV (sem alto-falante nem microfone, para não atrapalhar o
     * Arara aberto), com ruído de fundo, tocado no engine em tempo real. Mede, por trecho, quando foi cortado, quanto
     * esperou na fila e quanto o Whisper levou, e a latência da parada até o texto final.
     * "respira": com pontuação, a voz pausa nas vírgulas e pontos. "corrido": sem pontuação e mais rápido, quase
     * sem pausa, como fala real de quem não para.
     */
    static void latencia(Settings.Model only, String onlyKind, double tail, boolean withPreview) throws Exception {
        log("== Latência de ditado longo ==");
        Settings.Model[] models = only != null ? new Settings.Model[] {only}
                : new Settings.Model[] {Settings.Model.LEVE, Settings.Model.EQUILIBRADO, Settings.Model.PRECISO};
        for (String kind : new String[] {"respira", "corrido"}) {
            if (onlyKind != null && !kind.equals(onlyKind)) continue;
            Path wav = OUT.resolve("latencia-" + kind + ".wav");
            if (!Files.exists(wav)) synth(kind.equals("respira") ? LONGA : LONGA.replaceAll("[,.]", ""),
                    kind.equals("respira") ? 0 : 1, wav);
            short[] full = withNoise(readWav(wav), tail);
            Vad v = new Vad();
            List<Double> pauses = new ArrayList<>();
            long quiet = 0;
            for (int i = 0; i + 512 <= full.length; i += 512) {
                long before = v.speechEnd();
                v.feed(Arrays.copyOfRange(full, i, i + 512));
                if (v.speechEnd() != before && before > 0 && quiet > 0) pauses.add((v.speechEnd() - before - 512.0 * Vad.SPEECH_CHUNKS) / Vad.RATE);
                quiet = v.speechEnd() == before ? quiet + 1 : 0;
            }
            double speech = (v.speechEnd() - v.speechStart()) / (double) Vad.RATE;
            long over6 = pauses.stream().filter(p -> p >= 0.6).count(), over3 = pauses.stream().filter(p -> p >= 0.3).count();
            log("%s: áudio %.1f s, fala %.1f s; pausas >= 0,3 s: %d, >= 0,6 s: %d, maior %.2f s", kind,
                    full.length / (double) Vad.RATE, speech, over3, over6, pauses.stream().mapToDouble(d -> d).max().orElse(0));
            for (Settings.Model m : models) latenciaRun(m, kind, full, speech, withPreview);
        }
    }

    static void latenciaRun(Settings.Model m, String kind, short[] full, double speech, boolean withPreview) throws Exception {
        WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> m);
        w.prepare((done, total) -> {});
        w.transcribe(Arrays.copyOf(full, Vad.RATE * 2)); // aquece
        WhisperTranscriber.Preview preview = withPreview ? new WhisperTranscriber.Preview(MODELS, () -> m) : null;
        if (preview != null) {
            preview.transcribe(Arrays.copyOf(full, Vad.RATE * 2)); // carrega
            preview.transcribe(Arrays.copyOf(full, Vad.RATE * 2)); // aquece
        }
        long t0 = System.nanoTime();
        List<String> rows = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<double[]> cuts = new ArrayList<>(); // [áudio no corte, relógio no corte]
        double[] busy = {0};
        Transcriber logged = new Transcriber() {
            @Override public String transcribe(short[] pcm) { return transcribe(pcm, ""); }
            @Override public String transcribe(short[] pcm, String previous) {
                long s = System.nanoTime();
                String r = w.transcribe(pcm, previous);
                double start = (s - t0) / 1e9, took = (System.nanoTime() - s) / 1e9, len = pcm.length / (double) Vad.RATE;
                busy[0] += took;
                synchronized (cuts) {
                    double[] c = cuts.get(rows.size());
                    texts.add(r);
                    rows.add(String.format("| %d | %s | %.1f | %.1f | %.1f | %.1f | %.2f | %.2f |", rows.size() + 1, (int) c[2] == 0 ? "pausa"
                            : (int) c[2] == 1 ? "máx" : (int) c[2] == 2 ? "fim" : "respiro", c[0], len, c[1], start - c[1], took, took / len));
                }
                return r;
            }
        };
        Path data = Files.createTempDirectory("arara-e2e-latencia-");
        BlockingQueue<String> results = new LinkedBlockingQueue<>();
        DefaultDictationEngine engine = new DefaultDictationEngine(new JsonlHistoryStore(data.resolve("h.jsonl")),
                new JsonDictionaryStore(data.resolve("d.json")), logged, new Replay(full), results::add, preview);
        List<double[]> shown = new ArrayList<>(); // [áudio de, áudio até, relógio]: quando cada parte da fala apareceu
        double[] lastPreview = new double[1]; // fim do áudio já coberto pela prévia
        List<Double> previewTook = new ArrayList<>();
        engine.trace = s -> {
            String[] p = s.split(" ");
            synchronized (cuts) {
                if (p[0].equals("previa-ok")) lastPreview[0] = Math.max(lastPreview[0], Integer.parseInt(p[1]) / (double) Vad.RATE);
                if (p[0].equals("previa-fim")) previewTook.add(Double.parseDouble(p[1]));
            }
            if (!p[0].equals("corte")) return;
            synchronized (cuts) {
                cuts.add(new double[] {Integer.parseInt(p[2]) / (double) Vad.RATE, (System.nanoTime() - t0) / 1e9,
                        p[1].equals("pausa") ? 0 : p[1].equals("max") ? 1 : p[1].equals("fim") ? 2 : 3});
            }
        };
        List<String> partials = new ArrayList<>();
        engine.addListener(new DictationListener() {
            @Override public void onError(String msg) { results.add("ERRO: " + msg); }
            @Override public void onPartialText(String text) {
                double now = (System.nanoTime() - t0) / 1e9;
                synchronized (partials) { partials.add(String.format("%.1f s: %d palavras", now, words(text).length)); }
                synchronized (cuts) { shown.add(new double[] {0, cuts.get(partials.size() - 1)[0], now}); }
            }
            @Override public void onLiveText(String confirmed, String provisional) {
                double now = (System.nanoTime() - t0) / 1e9;
                synchronized (cuts) { if (!provisional.isEmpty()) shown.add(new double[] {0, lastPreview[0], now}); }
            }
        });
        engine.toggle();
        Replay.done.await();
        long stop = System.nanoTime();
        engine.toggle();
        String text = String.valueOf(results.poll(10, TimeUnit.MINUTES));
        double lat = (System.nanoTime() - stop) / 1e9;
        engine.close();
        w.close();
        if (preview != null) preview.close();
        double wer = wer(LONGA, text);
        // Para cada 0,1 s de fala (antes da parada): quanto depois de dito apareceu no pop-up (prévia ou confirmado).
        List<Double> delays = new ArrayList<>();
        Vad v = new Vad();
        double stopAt = (stop - t0) / 1e9;
        for (int i = 0; i + 512 <= full.length; i += 512) {
            long before = v.speechEnd();
            v.feed(Arrays.copyOfRange(full, i, i + 512));
            double tau = (i + 512) / (double) Vad.RATE;
            if (v.speechEnd() == before || tau > stopAt || i % 1536 != 0) continue;
            double first = Double.MAX_VALUE;
            synchronized (cuts) {
                for (double[] e : shown) if (e[0] <= tau && e[1] >= tau) first = Math.min(first, e[2]);
            }
            if (first < Double.MAX_VALUE && first < stopAt) delays.add(first - tau);
        }
        log("%s %s: fala %.1f s, parada em %.1f s", m, kind, speech, (stop - t0) / 1e9);
        log("| # | corte | áudio no corte (s) | trecho (s) | cortado em (s) | fila (s) | Whisper (s) | RTF |");
        rows.forEach(E2E::log);
        for (int i = 0; i < texts.size(); i++) log("trecho %d: %s", i + 1, texts.get(i));
        if (!partials.isEmpty()) log("texto parcial: %s", String.join("; ", partials));
        synchronized (cuts) {
            StringBuilder b = new StringBuilder();
            for (double[] e : shown) b.append(String.format("[%.1f-%.1f em %.1f] ", e[0], e[1], e[2]));
            log("na tela (áudio de-até, relógio): %s", b);
        }
        log("texto: %s", text);
        result("Latência " + m + " " + kind + (withPreview ? " com prévia" : ""), true,
                "latência %.2f s, WER %.1f%%, %d trechos, Whisper total %.1f s (RTF %.2f); fala na tela antes da parada: "
                + "atraso p50 %.1f s, p90 %.1f s (%d de cada 100 pontos); prévias %d (eventos com prévia %d), Whisper leve p50 %.2f s, máx %.2f s",
                lat, wer * 100, rows.size(), busy[0], busy[0] / speech, pct(delays, 50), pct(delays, 90),
                Math.round(100.0 * delays.size() / Math.max(1, countSpeech(full, stopAt))), previewTook.size(),
                shown.size() - partials.size(),
                pct(previewTook, 50), previewTook.stream().mapToDouble(d -> d).max().orElse(0));
    }

    /** Pontos de 0,1 s com fala até a parada (o mesmo critério do atraso no {@link #latenciaRun}). */
    static int countSpeech(short[] full, double stopAt) {
        Vad v = new Vad();
        int n = 0;
        for (int i = 0; i + 512 <= full.length; i += 512) {
            long before = v.speechEnd();
            v.feed(Arrays.copyOfRange(full, i, i + 512));
            if (v.speechEnd() != before && (i + 512) / (double) Vad.RATE <= stopAt && i % 1536 == 0) n++;
        }
        return n;
    }

    /** Tempo do Whisper por tamanho de trecho, sem e com o texto anterior no prompt: separa custo fixo e por segundo. */
    static void bench(Settings.Model only) throws Exception {
        log("== Whisper por tamanho de trecho (%d threads) ==", WhisperTranscriber.THREADS);
        short[] full = readWav(OUT.resolve("latencia-respira.wav"));
        String previous = LONGA.substring(0, 300);
        for (Settings.Model m : Settings.Model.values()) {
            if (only != null && m != only) continue;
            WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> m);
            w.prepare((done, total) -> {});
            w.transcribe(Arrays.copyOf(full, Vad.RATE * 2));
            for (int len : new int[] {3, 5, 8, 10, 15, 20}) {
                short[] pcm = Arrays.copyOfRange(full, Vad.RATE * 10, Vad.RATE * (10 + len));
                double[] t = new double[2];
                for (int rep = 0; rep < 2; rep++) {
                    for (int p = 0; p < 2; p++) {
                        long s = System.nanoTime();
                        w.transcribe(pcm, p == 0 ? "" : previous);
                        t[p] += (System.nanoTime() - s) / 2e9;
                    }
                }
                log("| %s | %d s | %.2f s | %.2f s |", m, len, t[0], t[1]);
            }
            w.close();
        }
    }

    /**
     * Comandos de voz de ponta a ponta: frase sintetizada com ruído de fundo -> CommandListener (VAD, corte do trecho
     * curto) -> Whisper LEVE -> VoiceCommand. Positivos têm que virar o comando certo; negativos (frase longa com a
     * palavra, palavra solta comum, silêncio) não podem virar comando nenhum. Duas velocidades de fala.
     */
    static void voz() throws Exception {
        log("== Comandos de voz ==");
        Object[][] casos = {
                {"Arara.", VoiceCommand.ARARA}, {"Oi, Arara!", VoiceCommand.ARARA}, {"Pronto.", VoiceCommand.PRONTO},
                {"Aprovado!", VoiceCommand.APROVADO}, {"Cancela.", VoiceCommand.CANCELA},
                {"A arara azul voou longe hoje de manhã.", null}, {"O orçamento foi aprovado ontem à tarde.", null},
                {"Obrigado.", null}, {"Tá bom.", null}, {"Sim.", null}, {"", null}};
        WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> Settings.Model.LEVE);
        w.prepare((done, total) -> {});
        {
        int ok = 0, total = 0, falsos = 0;
        List<Double> latencias = new ArrayList<>();
        for (int rate : new int[] {0, 2}) {
            for (Object[] caso : casos) {
                String frase = (String) caso[0];
                VoiceCommand esperado = (VoiceCommand) caso[1];
                short[] voice = new short[0];
                if (!frase.isEmpty()) {
                    Path wav = OUT.resolve("voz.wav");
                    synth(frase, rate, wav);
                    voice = readWav(wav);
                }
                List<String> ouvidos = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<Double> tempos = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<String> textos = new java.util.concurrent.CopyOnWriteArrayList<>();
                VoiceCommandRecognizer medido = pcm -> {
                    long t0 = System.nanoTime();
                    String texto;
                    try {
                        texto = w.transcribeCommand(pcm); // o mesmo caminho do app
                    } catch (RuntimeException e) {
                        log("    exceção no Whisper: %s", e);
                        throw e;
                    }
                    textos.add(texto);
                    VoiceCommand c = VoiceCommand.match(texto);
                    tempos.add((System.nanoTime() - t0) / 1e9);
                    ouvidos.add(java.util.Optional.ofNullable(c).map(Enum::name).orElse("-"));
                    return c;
                };
                java.util.function.Consumer<short[]>[] alvo = new java.util.function.Consumer[1];
                DefaultDictationEngine.AudioSource fake = new DefaultDictationEngine.AudioSource() {
                    public void start(java.util.function.Consumer<short[]> onChunk) { alvo[0] = onChunk; }
                    public void stop() { alvo[0] = null; }
                };
                DictationEngine parado = new DictationEngine() { // só para o ouvinte ver o motor parado
                    public void toggle() {}
                    public void cancel() {}
                    public DictationState state() { return DictationState.IDLE; }
                    public void addListener(DictationListener l) {}
                    public void removeListener(DictationListener l) {}
                    public void close() {}
                };
                CommandListener ouvinte = new CommandListener(parado, fake, medido, () -> true);
                ouvinte.sync();
                short[] pcm = withNoise(voice, 1.0);
                Vad diag = new Vad();
                long primeiro = -1;
                for (int i = 0; i + 512 <= pcm.length; i += 512) {
                    diag.feed(Arrays.copyOfRange(pcm, i, i + 512));
                    if (primeiro < 0 && diag.burstStart() >= 0) primeiro = diag.burstStart();
                }
                log("    vad: fala %.2f-%.2f s, último trecho em %.2f s, total %.2f s, limiar %.0f dBFS", diag.speechStart() / 16000.0,
                        diag.speechEnd() / 16000.0, diag.burstStart() / 16000.0, pcm.length / 16000.0, diag.threshold());
                for (int i = 0; i + 512 <= pcm.length && alvo[0] != null; i += 512) alvo[0].accept(Arrays.copyOfRange(pcm, i, i + 512));
                Thread.sleep(100);
                for (int i = 0; i < 200 && ouvinte.recognizing(); i++) Thread.sleep(50); // espera o Whisper (até 10 s)
                ouvinte.close();
                VoiceCommand ouvido = ouvidos.stream().filter(n -> !n.equals("-")).map(VoiceCommand::valueOf).findFirst().orElse(null);
                boolean certo = ouvido == esperado;
                total++;
                if (certo) ok++;
                if (esperado == null && ouvido != null) falsos++;
                latencias.addAll(tempos);
                log("  [%s] velocidade %d, \"%s\": esperado %s, ouvido %s, %d trecho(s) ao Whisper%s", certo ? "ok" : "ERRO", rate,
                        frase.isEmpty() ? "(silêncio)" : frase, esperado, ouvido, tempos.size(),
                        tempos.isEmpty() ? "" : String.format(" em %.2f s: %s", tempos.get(0), textos));
            }
        }
        double media = latencias.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double pior = latencias.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        String resumo = String.format("%d/%d certos, %d falso(s) positivo(s), Whisper %.2f s em média (pior %.2f s)",
                ok, total, falsos, media, pior);
        result("Comandos de voz", ok == total && falsos == 0,
                "%s", resumo);
        }
        w.close();
    }

    /**
     * Diagnóstico com a voz de verdade: o mesmo CommandListener e o mesmo transcribeCommand do app, no microfone real.
     * Mostra cada trecho de fala (curto ou não), o que o Whisper escreveu e o comando reconhecido.
     */
    static void ouvir(int seconds, String mixer) throws Exception {
        log("== Ouvir comandos (%d s, microfone: %s) ==", seconds, mixer == null ? "padrão" : mixer);
        WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> Settings.Model.LEVE);
        w.prepare((done, total) -> {});
        Path dir = OUT.resolve("comandos");
        Files.createDirectories(dir);
        int[] n = {0};
        VoiceCommandRecognizer diag = pcm -> {
            long t0 = System.nanoTime();
            String texto = w.transcribeCommand(pcm);
            VoiceCommand c = VoiceCommand.match(texto);
            int i = ++n[0];
            try {
                writeWav(pcm, dir.resolve(String.format("%02d.wav", i)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            log("  trecho %02d (%.2f s de áudio, Whisper %.2f s): \"%s\" -> %s", i, pcm.length / 16000.0,
                    (System.nanoTime() - t0) / 1e9, texto, c);
            return c;
        };
        Microphone real = new Microphone(() -> mixer);
        Vad todos = new Vad(); // mostra também os trechos que o ouvinte descarta por tamanho
        long[] fed = {0}, visto = {-1};
        DefaultDictationEngine.AudioSource mic = new DefaultDictationEngine.AudioSource() {
            public void start(java.util.function.Consumer<short[]> onChunk) throws Exception {
                real.start(chunk -> {
                    onChunk.accept(chunk);
                    synchronized (todos) {
                        fed[0] += chunk.length;
                        todos.feed(chunk);
                        long b = todos.burstStart(), e = todos.speechEnd();
                        if (b >= 0 && b != visto[0] && fed[0] - e >= CommandListener.PAUSE) {
                            visto[0] = b;
                            double len = (e - b) / 16000.0;
                            String nota = len * Vad.RATE < CommandListener.MIN ? "curto demais: descartado"
                                    : len * Vad.RATE > CommandListener.MAX ? "longo demais: descartado" : "vai ao Whisper";
                            log("  fala em %.1f s, %.2f s (%s), nível %.0f dBFS, limiar %.0f", b / 16000.0, len, nota,
                                    Vad.dbfs(chunk), todos.threshold());
                        }
                    }
                });
            }

            public void stop() {
                real.stop();
            }
        };
        DictationEngine esperando = new DictationEngine() { // finge a espera da aprovação: o ouvinte fica com o microfone
            public void toggle() {}
            public void cancel() {}
            public DictationState state() { return DictationState.AWAITING_APPROVAL; }
            public void addListener(DictationListener l) {}
            public void removeListener(DictationListener l) {}
            public void close() {}
        };
        CommandListener ouvinte = new CommandListener(esperando, mic, diag, () -> true);
        bipe(880);
        ouvinte.sync();
        log("  ouvindo... fale agora");
        Thread.sleep(seconds * 1000L);
        ouvinte.close();
        bipe(440);
        w.close();
        log("  %d trecho(s) foram ao Whisper; áudio em %s", n[0], dir.toAbsolutePath());
    }

    /**
     * Fluxo de voz completo, sem microfone: o motor e o ouvinte de verdade ouvem o mesmo "ar" (uma linha do tempo tocada
     * no ritmo real), com os trechos gravados pelo "ouvir": "Arara", uma frase, "Pronto" e "Aprovado".
     */
    static void fluxoVoz() throws Exception {
        log("== Fluxo de voz (Arara -> frase -> Pronto -> Aprovado), falando na hora certa ==");
        Path dir = OUT.resolve("comandos");
        WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> Settings.Model.LEVE);
        w.prepare((done, total) -> {});
        WhisperTranscriber leve = new WhisperTranscriber(MODELS, () -> Settings.Model.LEVE); // como a prévia do app
        leve.prepare((done, total) -> {});
        Path frase = OUT.resolve("frase.wav");
        synth("Isto é um teste do ditado por voz.", 0, frase);
        Ar ar = new Ar();
        Path tmp = Files.createTempDirectory("arara-fluxo-voz");
        SettingsStore settings = new JsonSettingsStore(tmp.resolve("config.json"));
        settings.save(Settings.defaults().withVoiceWake(true).withVoiceApproval(true));
        List<String> colados = new java.util.concurrent.CopyOnWriteArrayList<>();
        DefaultDictationEngine engine = new DefaultDictationEngine(new JsonlHistoryStore(tmp.resolve("h.jsonl")),
                new JsonDictionaryStore(tmp.resolve("d.json")), w, ar.source("motor"), colados::add);
        VoiceCommandRecognizer rec = VoiceCommandRecognizer.whisper(() -> leve);
        engine.setVoice(settings, rec);
        CommandListener ouvinte = new CommandListener(engine, ar.source("ouvinte"), rec, () -> true);
        engine.addListener(ouvinte);
        long t0 = System.nanoTime();
        BlockingQueue<DictationState> estados = new LinkedBlockingQueue<>();
        engine.addListener(new DictationListener() {
            public void onState(DictationState st) {
                log("  %5.1f s  estado %s", (System.nanoTime() - t0) / 1e9, st);
                estados.add(st);
            }
            public void onError(String m) { log("  %5.1f s  erro: %s", (System.nanoTime() - t0) / 1e9, m); }
        });
        ouvinte.sync();
        Thread som = new Thread(() -> ar.play(t0), "ar");
        som.setDaemon(true);
        som.start();
        Thread.sleep(1000);
        ar.fala("Arara", readWav(dir.resolve("06.wav")), t0);
        esperar(estados, DictationState.LISTENING, 10);
        Thread.sleep(500);
        ar.fala("frase", withNoise(readWav(frase), 0), t0);
        Thread.sleep(1200);
        ar.fala("pausa de 1 s", ruido(1.0), t0); // a fila toca em sequência: a pausa entra como ruído
        ar.fala("Pronto", readWav(dir.resolve("08.wav")), t0);
        boolean espera = esperar(estados, DictationState.AWAITING_APPROVAL, 20);
        Thread.sleep(800); // a pessoa lê o texto e responde
        ar.fala("Aprovado", readWav(dir.resolve("01.wav")), t0);
        boolean colou = esperar(estados, DictationState.PASTED, 10);
        ouvinte.close();
        engine.close();
        w.close();
        leve.close();
        boolean semPronto = colados.size() == 1 && !colados.get(0).toLowerCase().contains("pronto");
        result("Fluxo de voz", espera && colou && semPronto, "colado: %s", colados);
    }

    /** Trechos gravados pelo "ouvir" contra ajustes do Whisper, cada um 6 vezes com o recorte deslocado. */
    static void bancada() throws Exception {
        Path dir = OUT.resolve("comandos");
        WhisperTranscriber w = new WhisperTranscriber(MODELS, () -> Settings.Model.LEVE);
        w.prepare((done, total) -> {});
        java.util.Map<String, java.util.function.Consumer<io.github.givimad.whisperjni.WhisperFullParams>> v = new java.util.LinkedHashMap<>();
        v.put("ordem-ACPA", p -> p.initialPrompt = "Aprovado Cancela Pronto Arara");
        v.put("ordem-ACPA+semts", p -> { p.initialPrompt = "Aprovado Cancela Pronto Arara"; p.noTimestamps = true; });
        v.put("ordem-CAAP", p -> p.initialPrompt = "Cancela Aprovado Arara Pronto");
        v.put("ordem-PACA", p -> p.initialPrompt = "Pronto Aprovado Cancela Arara");
        v.put("ordem-ACPA.", p -> p.initialPrompt = "Aprovado Cancela Pronto Arara.");
        v.put("frase-natural", p -> p.initialPrompt = "Comandos: Arara, Pronto, Aprovado e Cancela.");
        v.put("frase-natural+semts", p -> { p.initialPrompt = "Comandos: Arara, Pronto, Aprovado e Cancela."; p.noTimestamps = true; });
        java.util.Random rnd = new java.util.Random(3);
        File[] wavs = dir.toFile().listFiles((d, n) -> n.endsWith(".wav"));
        Arrays.sort(wavs);
        for (var e : v.entrySet()) {
            int certos = 0, total = 0, lacos = 0;
            double soma = 0, pior = 0;
            for (File f : wavs) {
                short[] base = readWav(f.toPath());
                for (int k = 0; k < 6; k++) {
                    int desloca = rnd.nextInt(Vad.RATE / 10); // tira até 0,1 s do começo: muda o recorte
                    short[] pcm = Arrays.copyOfRange(base, desloca, base.length);
                    long t0 = System.nanoTime();
                    String texto = w.transcribeCommand(pcm, e.getValue());
                    double t = (System.nanoTime() - t0) / 1e9;
                    soma += t;
                    pior = Math.max(pior, t);
                    total++;
                    if (texto.length() > 30) lacos++;
                    if (VoiceCommand.match(texto) != null) certos++;
                }
            }
            log("  %-20s %2d/%d reconhecidos, %d laço(s), %.2f s em média, pior %.2f s", e.getKey(), certos, total, lacos,
                    soma / total, pior);
        }
        w.close();
    }

    static boolean esperar(BlockingQueue<DictationState> q, DictationState alvo, int segundos) throws InterruptedException {
        long fim = System.nanoTime() + segundos * 1_000_000_000L;
        for (DictationState st; (st = q.poll(Math.max(1, fim - System.nanoTime()), TimeUnit.NANOSECONDS)) != null; ) {
            if (st == alvo) return true;
        }
        log("  (não chegou em %s em %d s)", alvo, segundos);
        return false;
    }

    static short[] ruido(double seconds) {
        java.util.Random rnd = new java.util.Random(7);
        short[] out = new short[(int) (seconds * Vad.RATE)];
        for (int i = 0; i < out.length; i++) out[i] = (short) (rnd.nextGaussian() * 60);
        return out;
    }

    /** O "ar": ruído de fundo sem parar, tocado em tempo real para as fontes abertas (motor e ouvinte), mais as falas. */
    static final class Ar {
        final java.util.Map<String, java.util.function.Consumer<short[]>> abertas = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentLinkedQueue<Short> fila = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile boolean tocando = true;

        DefaultDictationEngine.AudioSource source(String nome) {
            return new DefaultDictationEngine.AudioSource() {
                public void start(java.util.function.Consumer<short[]> onChunk) {
                    abertas.put(nome, onChunk);
                    log("           microfone do %s aberto", nome);
                }

                public void stop() {
                    if (abertas.remove(nome) != null) log("           microfone do %s fechado", nome);
                }
            };
        }

        void fala(String oque, short[] pcm, long t0) {
            log("  %5.1f s  (fala: %s)", (System.nanoTime() - t0) / 1e9, oque);
            for (short v : pcm) fila.add(v);
        }

        void play(long t0) {
            java.util.Random rnd = new java.util.Random(7);
            long next = System.nanoTime();
            while (tocando) {
                next += 32_000_000;
                long wait = next - System.nanoTime();
                if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                short[] chunk = new short[512];
                for (int i = 0; i < chunk.length; i++) {
                    Short v = fila.poll();
                    chunk[i] = v != null ? v : (short) (rnd.nextGaussian() * 60);
                }
                for (var c : abertas.values()) c.accept(chunk);
            }
        }
    }

    /** Bipe de 0,3 s: marca o começo e o fim da escuta. */
    static void bipe(int hz) throws Exception {
        AudioFormat f = new AudioFormat(44100, 16, 1, true, false);
        byte[] b = new byte[44100 * 3 / 10 * 2];
        for (int i = 0; i < b.length / 2; i++) {
            short v = (short) (Math.sin(2 * Math.PI * hz * i / 44100.0) * 9000);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        try (javax.sound.sampled.SourceDataLine line = AudioSystem.getSourceDataLine(f)) {
            line.open(f);
            line.start();
            line.write(b, 0, b.length);
            line.drain();
        }
    }

    /** Fala o texto com a voz pt-BR direto num WAV de 16 kHz mono. */
    static void synth(String text, int rate, Path wav) throws Exception {
        String ps = "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'pt-BR' } | Select-Object -First 1; "
                + "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }; $s.Rate = " + rate + "; "
                + "$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 'Sixteen', 'Mono'); "
                + "$s.SetOutputToWaveFile('" + wav.toAbsolutePath() + "', $f); $s.Speak('" + text.replace("'", "''") + "'); $s.Dispose()";
        new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).inheritIO().start().waitFor();
    }

    /**
     * 1,5 s antes e {@code tail} s depois da última palavra (o silêncio do fim do WAV sai: é quando a pessoa aperta o
     * atalho), com ruído de fundo de ~-55 dBFS.
     */
    static short[] withNoise(short[] voice, double tail) {
        int last = voice.length;
        while (last > 0 && Math.abs(voice[last - 1]) < 300) last--;
        voice = Arrays.copyOf(voice, last);
        short[] out = new short[voice.length + (int) (Vad.RATE * (1.5 + tail))];
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < out.length; i++) {
            int j = i - Vad.RATE * 3 / 2;
            double s = (j >= 0 && j < voice.length ? voice[j] * 0.5 : 0) + rnd.nextGaussian() * 60;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
        return out;
    }

    /** Toca a gravação no engine em blocos de 512 amostras, no ritmo do microfone de verdade. */
    static final class Replay implements DefaultDictationEngine.AudioSource {
        static java.util.concurrent.CountDownLatch done;
        final short[] pcm;
        volatile boolean running;

        Replay(short[] pcm) {
            this.pcm = pcm;
            done = new java.util.concurrent.CountDownLatch(1);
        }

        @Override
        public void start(java.util.function.Consumer<short[]> onChunk) {
            running = true;
            Thread t = new Thread(() -> {
                long next = System.nanoTime();
                for (int i = 0; i + 512 <= pcm.length && running; i += 512) {
                    next += 32_000_000; // 512 amostras a 16 kHz
                    long wait = next - System.nanoTime();
                    if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    onChunk.accept(Arrays.copyOfRange(pcm, i, i + 512));
                }
                done.countDown();
            }, "arara-microfone");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    static short[] join(List<short[]> chunks) {
        short[] all = new short[chunks.stream().mapToInt(c -> c.length).sum()];
        int off = 0;
        for (short[] c : chunks) {
            System.arraycopy(c, 0, all, off, c.length);
            off += c.length;
        }
        return all;
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
            core.settings().save(s.withModel(Settings.Model.LEVE).withMicrophone(null).withEscCancels(true));
            core.prepareModel((done, total) -> {});
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
