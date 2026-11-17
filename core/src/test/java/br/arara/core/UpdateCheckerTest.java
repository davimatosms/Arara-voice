package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.arara.core.UpdateChecker.Update;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerTest {
    static final byte[] SETUP = "instalador falso".getBytes(StandardCharsets.UTF_8);
    static final String FILE = "v0.3.0/Arara-Setup-0.3.0.exe";

    @TempDir
    Path dir;
    HttpServer server;
    String base;
    final AtomicReference<String> manifest = new AtomicReference<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final List<List<String>> ran = new CopyOnWriteArrayList<>();
    final List<List<String>> started = new CopyOnWriteArrayList<>();
    SettingsStore settings;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = ex.getRequestURI().getPath().equals("/versao.json")
                    ? manifest.get().getBytes(StandardCharsets.UTF_8) : SETUP;
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/download/";
        manifest.set(manifest("0.3.0", base + FILE, sha(SETUP)));
        settings = new JsonSettingsStore(dir.resolve("config.json"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String manifest(String version, String url, String sha) {
        return """
                {"versao": "%s", "data": "2026-12-01", "notas": "Correções.",
                 "arquivos": {"windows": {"url": "%s", "sha256": "%s"}}}""".formatted(version, url, sha);
    }

    UpdateChecker checker(String allowed, String exe, boolean mac) {
        return checker(allowed, exe, "windows", mac, false);
    }

    UpdateChecker checker(String allowed, String exe, String platform, boolean mac, boolean linux) {
        return checker(platform, allowed, exe, mac, linux, null);
    }

    UpdateChecker checker(String platform, String allowed, String exe, boolean mac, String appImage) {
        return checker(platform, allowed, exe, mac, platform.startsWith("linux"), appImage);
    }

    UpdateChecker checker(String platform, String allowed, String exe, boolean mac, boolean linux, String appImage) {
        return new UpdateChecker(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/versao.json"),
                "0.2.0", platform, settings, allowed, Optional.ofNullable(exe), mac, linux,
                new UpdateChecker.Runner() {
                    public int run(List<String> cmd) {
                        ran.add(cmd);
                        return 0;
                    }

                    public void start(List<String> cmd) {
                        started.add(cmd);
                    }
                }, dir.resolve("tmp"), 4242, appImage);
    }

    UpdateChecker windows() {
        return checker(base, "C:\\Users\\a\\AppData\\Local\\Programs\\Arara\\Arara.exe", false);
    }

    static String linuxManifest(String version, String url, String sha) {
        return """
                {"versao": "%s", "data": "2026-12-01", "notas": "Correções.",
                 "arquivos": {"linux-x64": {"url": "%s", "sha256": "%s"}}}""".formatted(version, url, sha);
    }

    @Test
    void comparesVersions() {
        assertTrue(UpdateChecker.compare("0.10.0", "0.9.9") > 0);
        assertTrue(UpdateChecker.compare("0.2.1", "0.2.0") > 0);
        assertTrue(UpdateChecker.compare("1.0.0", "0.99.99") > 0);
        assertEquals(0, UpdateChecker.compare("0.2", "0.2.0"));
        assertTrue(UpdateChecker.compare("0.1.9", "0.2.0") < 0);
    }

    @Test
    void offersNewerVersionWithGenericUserAgent() {
        Update u = windows().check().orElseThrow();
        assertEquals("0.3.0", u.version());
        assertEquals("Correções.", u.notes());
        assertEquals(List.of("/versao.json"), requests);
        assertEquals(List.of("Arara/0.2.0"), userAgents);
    }

    @Test
    void sameOrOlderVersionIsSilent() {
        manifest.set(manifest("0.2.0", base + FILE, "a".repeat(64)));
        assertTrue(windows().check().isEmpty());
        manifest.set(manifest("0.1.0", base + FILE, "a".repeat(64)));
        assertTrue(windows().check().isEmpty());
    }

    @Test
    void skippedVersionIsSilentButNextOneIsOffered() {
        UpdateChecker c = windows();
        c.skip(c.check().orElseThrow());
        assertEquals("0.3.0", new JsonSettingsStore(dir.resolve("config.json")).load().skippedVersion());
        assertTrue(c.check().isEmpty());
        manifest.set(manifest("0.3.1", base + "v0.3.1/Arara-Setup-0.3.1.exe", sha(SETUP)));
        assertEquals("0.3.1", c.check().orElseThrow().version());
    }

    @Test
    void settingOffMakesNoRequest() {
        Settings x = Settings.defaults();
        settings.save(x.withCheckUpdates(false).withSkippedVersion(null));
        assertTrue(windows().check().isEmpty());
        assertEquals(List.of(), requests);
    }

    @Test
    void searchNowIgnoresSettingAndSkipButReportsNoInternet() throws Exception {
        UpdateChecker c = windows();
        c.skip(c.check().orElseThrow());
        Settings x = settings.load();
        settings.save(x.withCheckUpdates(false));
        assertEquals("0.3.0", c.latest().orElseThrow().version());
        assertEquals("0.2.0", c.current());
        manifest.set(manifest("0.2.0", base + FILE, "a".repeat(64)));
        assertTrue(c.latest().isEmpty());
        manifest.set("lixo");
        assertThrows(IOException.class, c::latest);
        server.stop(0);
        assertThrows(IOException.class, c::latest);
    }

    @Test
    void cancelStopsDownloadWithoutRunning() throws Exception {
        Update u = windows().check().orElseThrow();
        assertFalse(windows().install(u, (d, t) -> {}, () -> true));
        assertEquals(List.of(), started);
    }

    @Test
    void invalidManifestOrNoInternetIsSilent() {
        for (String bad : List.of("lixo", "{}", "{\"versao\":\"0.3.0\"}", manifest("três", base + FILE, "a".repeat(64)),
                manifest("0.3.0", base + FILE, "nao-e-sha"), "{\"versao\":\"0.3.0\",\"arquivos\":{\"mac-arm64\":{}}}")) {
            manifest.set(bad);
            assertTrue(windows().check().isEmpty(), bad);
        }
        server.stop(0);
        assertTrue(windows().check().isEmpty());
    }

    @Test
    void refusesUrlsOutsideTheRepoReleases() {
        String ok = UpdateChecker.RELEASES;
        for (String bad : List.of("https://github.com/outro/Arara-voice/releases/download/v1/a.exe",
                "http://github.com/davimatosms/Arara-voice/releases/download/v1/a.exe",
                "https://github.com.evil.com/davimatosms/Arara-voice/releases/download/v1/a.exe",
                "https://evil.com/?https://github.com/davimatosms/Arara-voice/releases/download/v1/a.exe",
                ok + "../../../outro/a.exe", ok + "v1/../../a.exe", ok + "v1/a.exe?x=1", ok + "v1/%2e%2e/a.exe",
                ok + "v1/sub/a.exe")) {
            assertThrows(IOException.class, () -> UpdateChecker.checkUrl(bad, ok), bad);
        }
        assertEquals(URI.create(ok + "v0.2.0/Arara-Setup-0.2.0.exe"),
                assertDoesNotThrowUrl(ok + "v0.2.0/Arara-Setup-0.2.0.exe", ok));
        // o manifesto apontando para fora do repo é ignorado
        manifest.set(manifest("0.3.0", "https://evil.com/Arara-Setup.exe", sha(SETUP)));
        assertTrue(checker(UpdateChecker.RELEASES, null, false).check().isEmpty());
    }

    static URI assertDoesNotThrowUrl(String url, String allowed) {
        try {
            return UpdateChecker.checkUrl(url, allowed);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void wrongShaDeletesAndNeverRuns() {
        Update u = new Update("0.3.0", "", "", URI.create(base + FILE), "0".repeat(64));
        assertThrows(UpdateChecker.Corrupted.class, () -> windows().install(u, (d, t) -> {}));
        assertEquals(List.of(), started);
        assertEquals(List.of(), ran);
        assertFalse(Files.exists(dir.resolve("tmp/Arara-Setup-0.3.0.exe")));
        assertFalse(Files.exists(dir.resolve("tmp/Arara-Setup-0.3.0.exe.part")));
    }

    @Test
    void installRefusesUrlOutsideRepo() {
        Update u = new Update("0.3.0", "", "", URI.create(base + FILE), "0".repeat(64));
        assertThrows(IOException.class, () -> checker(UpdateChecker.RELEASES, "C:\\Arara\\Arara.exe", false)
                .install(u, (d, t) -> {}));
        assertEquals(List.of(), requests);
    }

    @Test
    void outsidePackagedAppDoesNothing() throws Exception {
        Update u = windows().check().orElseThrow();
        requests.clear();
        assertFalse(checker(base, "C:\\Program Files\\Java\\bin\\java.exe", false).install(u, (d, t) -> {}));
        assertEquals(List.of(), requests);
        assertEquals(List.of(), started);
    }

    @Test
    void windowsDownloadsWithProgressAndStartsSilentInno() throws Exception {
        Update u = windows().check().orElseThrow();
        List<Long> progress = new CopyOnWriteArrayList<>();
        assertTrue(windows().install(u, (d, t) -> progress.add(d)));
        Path setup = dir.resolve("tmp/Arara-Setup-0.3.0.exe");
        assertEquals(sha(SETUP), sha(Files.readAllBytes(setup)));
        assertEquals((long) SETUP.length, progress.get(progress.size() - 1));
        assertEquals(List.of(List.of(setup.toString(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/REABRIR=1")),
                started);
        assertEquals(List.of(), ran);
    }

    @Test
    void macMountsCopiesNextToAppAndSwapsAfterExit() throws Exception {
        Path apps = dir.resolve("Applications");
        Files.createDirectories(apps.resolve("Arara.app/Contents/MacOS"));
        String exe = apps + "/Arara.app/Contents/MacOS/Arara";
        Update u = windows().check().orElseThrow(); // mesmo arquivo de teste; a plataforma não muda o fluxo
        assertTrue(checker(base, exe, true).install(u, (d, t) -> {}));
        Path tmp = dir.resolve("tmp");
        Path app = apps.resolve("Arara.app");
        Path staged = apps.resolve(".Arara-nova.app");
        assertEquals(List.of(
                List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", tmp.resolve("montagem").toString(),
                        tmp.resolve("Arara-Setup-0.3.0.exe").toString()),
                List.of("rm", "-rf", staged.toString()),
                List.of("ditto", tmp.resolve("montagem").resolve("Arara.app").toString(), staged.toString()),
                List.of("hdiutil", "detach", tmp.resolve("montagem").toString())), ran);
        assertEquals(List.of(List.of("/bin/sh", "-c", UpdateChecker.MAC_SWAP, "sh", "4242", staged.toString(),
                app.toString(), apps.resolve(".Arara-velha.app").toString())), started);
    }

    @Test
    void linuxReplacesAppImageAtomicallyAndRestarts() throws Exception {
        Path self = dir.resolve("Arara-x86_64.AppImage");
        Files.writeString(self, "velho");
        Update u = windows().check().orElseThrow();
        assertTrue(checker("linux-x64", base, null, false, self.toString()).install(u, (d, t) -> {}));
        assertEquals(sha(SETUP), sha(Files.readAllBytes(self)));
        assertFalse(Files.exists(dir.resolve(".Arara-nova.AppImage")));
        assertEquals(List.of(List.of("chmod", "+x", dir.resolve(".Arara-nova.AppImage").toString())), ran);
        assertEquals(List.of(List.of("/bin/sh", "-c", UpdateChecker.LINUX_RESTART, "sh", "4242", self.toString())),
                started);
    }

    @Test
    void linuxWithoutAppImageDoesNothing() throws Exception {
        Update u = windows().check().orElseThrow();
        assertFalse(checker("linux-x64", base, null, false, null).install(u, (d, t) -> {}));
        assertEquals(List.of(), ran);
    }

    @Test
    void macAppOutsideAppBundleDoesNothing() throws Exception {
        Update u = windows().check().orElseThrow();
        assertFalse(checker(base, "/usr/bin/java", true).install(u, (d, t) -> {}));
        assertEquals(List.of(), ran);
    }

    @Test
    void macReadOnlyFolderAsksForManualDownload() throws Exception {
        Path apps = dir.resolve("Applications");
        Files.createDirectories(apps.resolve("Arara.app/Contents/MacOS"));
        apps.toFile().setWritable(false);
        try {
            Assumptions.assumeFalse(Files.isWritable(apps), "o sistema não deixa a pasta só leitura (Windows)");
            Update u = windows().check().orElseThrow();
            requests.clear();
            assertThrows(UpdateChecker.ManualInstallNeeded.class,
                    () -> checker(base, apps + "/Arara.app/Contents/MacOS/Arara", true).install(u, (d, t) -> {}));
            assertEquals(List.of(), requests);
        } finally {
            apps.toFile().setWritable(true);
        }
    }

    @Test
    void publishedManifestIsValidForEveryPlatform() throws Exception {
        String json = Files.readString(Path.of("../site/versao.json"));
        // site/versao.json da 0.4.0 ainda não tem linux-*: só as plataformas publicadas
        for (String platform : List.of("windows", "mac-arm64", "mac-x64")) {
            Update u = UpdateChecker.parse(json, platform, UpdateChecker.RELEASES);
            assertTrue(u.url().toString().contains("/v" + u.version() + "/"), platform);
            String url = u.url().toString();
            assertTrue(url.substring(url.lastIndexOf('/')).contains(u.version()), "arquivo com a versão no nome");
        }
    }

    @Test
    void linuxSwapsAppImageAfterExit() throws Exception {
        Path app = dir.resolve("Arara-0.3.0-linux-x64.AppImage");
        Files.writeString(app, "appimage atual");
        String file = "v0.3.0/Arara-0.3.0-linux-x64.AppImage";
        manifest.set(linuxManifest("0.3.0", base + file, sha(SETUP)));
        UpdateChecker c = checker(base, app.toString(), "linux-x64", false, true);
        Update u = c.check().orElseThrow();
        assertTrue(c.install(u, (d, t) -> {}));
        Path staged = app.resolveSibling(".Arara-nova.AppImage");
        Path old = app.resolveSibling(".Arara-velha.AppImage");
        assertTrue(Files.exists(staged));
        assertEquals(List.of(), ran);
        assertEquals(List.of(List.of("/bin/sh", "-c", UpdateChecker.LINUX_SWAP, "sh", "4242", staged.toString(),
                app.toString(), old.toString())), started);
    }

    @Test
    void linuxNonAppImageDoesNothing() throws Exception {
        manifest.set(linuxManifest("0.3.0", base + "v0.3.0/Arara-0.3.0-linux-x64.AppImage", sha(SETUP)));
        UpdateChecker c = checker(base, "/usr/bin/java", "linux-x64", false, true);
        Update u = c.check().orElseThrow();
        assertFalse(c.install(u, (d, t) -> {}));
        assertEquals(List.of(), started);
    }
}
