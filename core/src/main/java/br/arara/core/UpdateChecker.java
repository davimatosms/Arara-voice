package br.arara.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Atualização com pergunta: consulta o versao.json do site ao abrir e a cada 24 h, avisa a UI quando há versão nova
 * e só baixa e instala quando o usuário aceita. Sem internet ou com erro, fica em silêncio (só log).
 * O único pedido é o GET do manifesto, com User-Agent "Arara/&lt;versão&gt;" e nada do usuário.
 *
 * <p>Uso pela UI: {@link #system}, {@link #start} com o callback, e no diálogo {@link #install} (Sim),
 * {@link #skip} (Pular esta versão) ou nada (Não).
 */
public final class UpdateChecker implements AutoCloseable {
    public static final URI MANIFEST = URI.create("https://arara-voice.vercel.app/versao.json");
    static final String RELEASES = "https://github.com/davimatosms/Arara-voice/releases/download/";
    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");
    // Espera o Arara antigo sair, troca o .app (guardando o antigo até o novo estar no lugar) e abre o novo.
    static final String MAC_SWAP = """
            while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
            rm -rf "$4"
            mv "$3" "$4" && { mv "$2" "$3" || mv "$4" "$3"; }
            rm -rf "$4"
            open "$3"
            """;

    /**
     * Versão nova do manifesto, já com o arquivo desta plataforma.
     *
     * @param notes notas curtas em PT-BR para o diálogo
     * @param date  data da release (AAAA-MM-DD)
     */
    public record Update(String version, String date, String notes, URI url, String sha256) {}

    /** O app está numa pasta sem permissão de escrita (macOS): a UI orienta baixar à mão no site. */
    public static final class ManualInstallNeeded extends IOException {
        ManualInstallNeeded(String message) {
            super(message);
        }
    }

    /** Roda comandos do sistema; os testes trocam por um que só anota. */
    interface Runner {
        /** Roda e espera; devolve o código de saída. */
        int run(List<String> cmd) throws IOException;

        /** Só dispara (instalador ou script que sobrevive ao Arara). */
        void start(List<String> cmd) throws IOException;
    }

    private final URI manifest;
    private final String current;
    private final String platform;
    private final SettingsStore settings;
    private final String allowed;
    private final Optional<String> exe;
    private final boolean mac;
    private final Runner runner;
    private final Path tmp;
    private final long pid;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private ScheduledExecutorService timer;

    UpdateChecker(URI manifest, String current, String platform, SettingsStore settings, String allowed,
            Optional<String> exe, boolean mac, Runner runner, Path tmp, long pid) {
        this.manifest = manifest;
        this.current = current;
        this.platform = platform;
        this.settings = settings;
        this.allowed = allowed;
        this.exe = exe;
        this.mac = mac;
        this.runner = runner;
        this.tmp = tmp;
        this.pid = pid;
    }

    /** Para o app: versão do jar (gradle.properties), plataforma e executável atuais. */
    public static UpdateChecker system(SettingsStore settings) {
        String platform = !AraraCore.MAC ? "windows"
                : System.getProperty("os.arch").equals("aarch64") ? "mac-arm64" : "mac-x64";
        return new UpdateChecker(MANIFEST, UpdateChecker.class.getPackage().getImplementationVersion(), platform,
                settings, RELEASES, ProcessHandle.current().info().command(), AraraCore.MAC, new Processes(),
                Path.of(System.getProperty("java.io.tmpdir"), "arara-atualizacao"), ProcessHandle.current().pid());
    }

    /**
     * Verifica agora (numa thread própria) e depois a cada 24 h. O callback roda nessa thread: use invokeLater.
     * Respeita "Verificar atualizações" e "Pular esta versão" a cada verificação.
     */
    public synchronized void start(Consumer<Update> onUpdateAvailable) {
        if (current == null) {
            LOG.info("Versão do app desconhecida (fora do jar): verificação de atualizações desligada.");
            return;
        }
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "arara-atualizacao");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> {
            try {
                check().ifPresent(onUpdateAvailable);
            } catch (RuntimeException e) { // exceção aqui cancelaria as próximas verificações
                LOG.warning("Callback de atualização falhou: " + e);
            }
        }, 0, 24, TimeUnit.HOURS);
    }

    /** Versão nova para oferecer, ou vazio (desligado, pulada, igual, sem internet ou manifesto inválido). */
    Optional<Update> check() {
        Settings s = settings.load();
        if (!s.checkUpdates() || current == null) return Optional.empty();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(manifest).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Arara/" + current).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            Update u = parse(r.body(), platform, allowed);
            if (compare(u.version(), current) <= 0 || u.version().equals(s.skippedVersion())) return Optional.empty();
            return Optional.of(u);
        } catch (IOException | RuntimeException e) {
            LOG.info("Verificação de atualizações falhou: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** "Pular esta versão": não avisa mais desta; a próxima versão avisa normalmente. */
    public void skip(Update u) {
        Settings x = settings.load();
        settings.save(new Settings(x.hotkey(), x.model(), x.accel(), x.microphone(), x.startWithWindows(),
                x.showPopup(), x.escCancels(), x.checkUpdates(), u.version()));
    }

    /**
     * "Sim": baixa o arquivo para a pasta temporária, confere o SHA-256 e dispara a instalação. Bloqueia: chame fora
     * da EDT. Se devolver true, a UI deve fechar o Arara já (System.exit): no Windows o instalador do Inno
     * substitui os arquivos e reabre o app; no macOS um script espera o Arara sair, troca o .app e o reabre.
     *
     * @return false fora do app instalado (rodando pelo Gradle): nada é baixado nem executado
     * @throws ManualInstallNeeded se o Arara.app está numa pasta sem permissão de escrita
     * @throws IOException         sem internet, URL fora das releases do Arara ou SHA-256 diferente (o arquivo é
     *                             apagado e nunca executado); a mensagem serve para o usuário
     */
    public boolean install(Update u, AraraCore.DownloadProgress progress) throws IOException {
        checkUrl(u.url().toString(), allowed);
        String path = exe.orElse("");
        if (!Startup.packaged(path, mac)) {
            LOG.info("Instalar a atualização " + u.version() + " ignorado fora do app instalado: " + path);
            return false;
        }
        Path app = mac ? Path.of(path.substring(0, path.length() - "/Contents/MacOS/Arara".length())) : null;
        if (mac && !(Files.isWritable(app.getParent()) && Files.isWritable(app))) {
            throw new ManualInstallNeeded("Sem permissão para substituir o Arara em " + app.getParent()
                    + ". Baixe a versão " + u.version() + " em arara-voice.vercel.app.");
        }
        String url = u.url().toString();
        Path file = tmp.resolve(url.substring(url.lastIndexOf('/') + 1));
        WhisperTranscriber.download(u.url(), u.sha256(), file, progress, () -> false); // SHA errado: apaga e lança
        if (!mac) {
            runner.start(windowsCommand(file));
            return true;
        }
        Path mnt = tmp.resolve("montagem");
        Files.createDirectories(mnt);
        Path staged = app.resolveSibling(".Arara-nova.app");
        run(List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", mnt.toString(), file.toString()));
        try {
            run(List.of("rm", "-rf", staged.toString()));
            // Copia já com o Arara rodando, ao lado do atual; a troca de verdade só acontece depois que ele sair.
            run(List.of("ditto", mnt.resolve("Arara.app").toString(), staged.toString()));
        } finally {
            try {
                run(List.of("hdiutil", "detach", mnt.toString()));
            } catch (IOException e) {
                LOG.warning("Não consegui desmontar o .dmg: " + e.getMessage());
            }
        }
        runner.start(List.of("/bin/sh", "-c", MAC_SWAP, "sh", Long.toString(pid), staged.toString(), app.toString(),
                app.resolveSibling(".Arara-velha.app").toString()));
        return true;
    }

    /** Inno em modo silencioso (só a janela de progresso); /REABRIR=1 abre o Arara no fim (veja arara.iss). */
    static List<String> windowsCommand(Path installer) {
        return List.of(installer.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/REABRIR=1");
    }

    private void run(List<String> cmd) throws IOException {
        if (runner.run(cmd) != 0) throw new IOException("A atualização falhou em " + cmd.get(0) + ". Tente de novo.");
    }

    @Override
    public synchronized void close() {
        if (timer != null) timer.shutdownNow();
    }

    /** @throws IOException manifesto sem versão, sem esta plataforma, com SHA-256 malformado ou URL fora do repo */
    static Update parse(String json, String platform, String allowed) throws IOException {
        Map<?, ?> m = Json.parse(json) instanceof Map<?, ?> map ? map : Map.of();
        if (!(m.get("versao") instanceof String version) || !VERSION.matcher(version).matches())
            throw new IOException("Manifesto sem versão válida.");
        if (!(m.get("arquivos") instanceof Map<?, ?> files) || !(files.get(platform) instanceof Map<?, ?> f)
                || !(f.get("url") instanceof String url) || !(f.get("sha256") instanceof String sha))
            throw new IOException("Manifesto sem o arquivo de " + platform + ".");
        if (!SHA256.matcher(sha).matches()) throw new IOException("SHA-256 inválido no manifesto.");
        return new Update(version, m.get("data") instanceof String d ? d : "",
                m.get("notas") instanceof String n ? n : "", checkUrl(url, allowed), sha);
    }

    /** Só arquivos das releases do Arara no GitHub: tag/arquivo, sem "..", consulta nem outro host. */
    static URI checkUrl(String url, String allowed) throws IOException {
        if (!url.matches(Pattern.quote(allowed) + "[\\w-][\\w.-]*/[\\w-][\\w.-]*"))
            throw new IOException("URL recusada, fora das releases do Arara: " + url);
        return URI.create(url);
    }

    /** Compara versões "1.2.3" número a número ("0.10.0" > "0.9.9"; "0.2" == "0.2.0"). */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    static final class Processes implements Runner {
        public int run(List<String> cmd) throws IOException {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                int code = p.waitFor();
                if (code != 0) LOG.warning(String.join(" ", cmd) + " saiu com " + code + ": " + out.strip());
                return code;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        public void start(List<String> cmd) throws IOException {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }
    }
}
