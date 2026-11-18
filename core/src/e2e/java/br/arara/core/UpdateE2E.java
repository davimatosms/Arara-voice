package br.arara.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Atualização do AppImage de ponta a ponta (CI do Linux): servidor http local com um versao.json falso (linux-x64)
 * e um "AppImage novo"; troca com SHA certo, recusa com SHA errado. Usa o UpdateChecker real (só o host permitido
 * e o Runner de reinício são trocados). Sai com 1 se algo falhar.
 */
public final class UpdateE2E {
    static int fails;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("arara-update-e2e");
        byte[] novo = "AppImage novo de mentira\n".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(novo));
        String ruim = "0".repeat(64);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        server.createContext("/versao-ok.json", x -> send(x, manifest(base, sha).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/versao-ruim.json", x -> send(x, manifest(base, ruim).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/v9.9.9/Arara-x86_64.AppImage", x -> send(x, novo));
        server.start();

        Path self = dir.resolve("Arara-x86_64.AppImage");
        Path backup = dir.resolve("velho.hardlink");
        for (String caso : new String[] {"ok", "ruim"}) {
            Files.writeString(self, "velho");
            Files.deleteIfExists(backup);
            Files.createLink(backup, self); // se a troca escrevesse "por cima", o hardlink mudaria junto
            List<List<String>> started = new ArrayList<>();
            UpdateChecker c = new UpdateChecker(URI.create(base + "versao-" + caso + ".json"), "0.4.3", "linux-x64",
                    new JsonSettingsStore(dir.resolve("config.json")), base, Optional.empty(), false,
                    new UpdateChecker.Runner() {
                        public int run(List<String> cmd) throws IOException {
                            try {
                                return new ProcessBuilder(cmd).inheritIO().start().waitFor();
                            } catch (InterruptedException e) {
                                throw new IOException(e);
                            }
                        }

                        public void start(List<String> cmd) {
                            started.add(cmd); // o reinício de verdade (exec do AppImage) não roda no teste
                        }
                    }, dir.resolve("tmp"), ProcessHandle.current().pid(), self.toString());
            UpdateChecker.Update u = c.latest().orElse(null);
            check("[" + caso + "] latest() acha 9.9.9 para linux-x64", u != null && u.version().equals("9.9.9"));
            if (u == null) continue;
            if (caso.equals("ok")) {
                check("[ok] install devolveu true", c.install(u, (d, t) -> {}));
                check("[ok] arquivo trocado pelo novo", Arrays.equals(Files.readAllBytes(self), novo));
                check("[ok] troca por rename (cópia velha intacta pelo hardlink)", "velho".equals(Files.readString(backup)));
                check("[ok] executável", Files.getPosixFilePermissions(self).contains(PosixFilePermission.OWNER_EXECUTE));
                check("[ok] sem .Arara-nova.AppImage sobrando", !Files.exists(dir.resolve(".Arara-nova.AppImage")));
                check("[ok] reinício disparado", started.size() == 1 && started.get(0).contains(self.toString()));
            } else {
                boolean recusou = false;
                try {
                    c.install(u, (d, t) -> {});
                } catch (UpdateChecker.Corrupted e) {
                    recusou = true;
                    System.out.println("mensagem: " + e.getMessage());
                }
                check("[ruim] SHA errado recusado (Corrupted)", recusou);
                check("[ruim] AppImage atual intacto", "velho".equals(Files.readString(self)));
                check("[ruim] sem .Arara-nova.AppImage sobrando", !Files.exists(dir.resolve(".Arara-nova.AppImage")));
                check("[ruim] nenhum reinício disparado", started.isEmpty());
            }
        }
        server.stop(0);
        System.out.println(fails == 0 ? "UPDATE E2E OK" : "UPDATE E2E FALHOU: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static String manifest(String base, String sha) {
        return "{\"versao\":\"9.9.9\",\"data\":\"2026-01-01\",\"notas\":\"teste\",\"arquivos\":{\"linux-x64\":{\"url\":\""
                + base + "v9.9.9/Arara-x86_64.AppImage\",\"sha256\":\"" + sha + "\"}}}";
    }

    static void send(HttpExchange x, byte[] body) throws IOException {
        x.sendResponseHeaders(200, body.length);
        x.getResponseBody().write(body);
        x.close();
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FALHA ") + what);
        if (!ok) fails++;
    }
}
