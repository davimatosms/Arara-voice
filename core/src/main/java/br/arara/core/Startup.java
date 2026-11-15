package br.arara.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Abrir com o sistema. Windows ("Iniciar com o Windows"): valor Arara em HKCU\...\Run com o caminho do Arara.exe.
 * macOS ("Abrir ao iniciar sessão"): LaunchAgent em ~/Library/LaunchAgents com o executável de dentro do Arara.app.
 * Só grava no app empacotado (jpackage); rodando pelo Gradle ou pelo java não toca em nada, para não registrar o java.
 */
final class Startup {
    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final Logger LOG = Logger.getLogger(Startup.class.getName());

    /** Onde fica o registro: o Run do registro do Windows ou a pasta de LaunchAgents do macOS. */
    interface Registry {
        void set(String name, String value) throws IOException;

        void delete(String name) throws IOException;
    }

    private final Registry registry;
    private final String name;
    private final Optional<String> command;
    private final boolean mac;

    Startup(Registry registry, String name, Optional<String> command, boolean mac) {
        this.registry = registry;
        this.name = name;
        this.command = command;
        this.mac = mac;
    }

    static Startup system() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (AraraCore.MAC) {
            Path agents = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
            return new Startup(new LaunchAgents(agents), LaunchAgents.LABEL, command, true);
        }
        return new Startup(new RegExe(), "Arara", command, false);
    }

    /** O processo é o app empacotado? Arara.exe no Windows, .../Arara.app/Contents/MacOS/Arara no macOS. */
    static boolean packaged(String exe, boolean mac) {
        if (mac) return exe.endsWith(".app/Contents/MacOS/Arara");
        // nome do arquivo à mão, sem Path: os testes rodam caminhos do Windows também no macOS
        return exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).equalsIgnoreCase("Arara.exe");
    }

    /** Liga ou desliga; ligado, regrava o caminho atual (corrige se o app mudou de pasta). */
    void sync(boolean on) {
        String exe = command.orElse("");
        if (!packaged(exe, mac)) {
            LOG.info("Abrir com o sistema (" + on + ") ignorado fora do app instalado: " + exe);
            return;
        }
        try {
            if (on) registry.set(name, mac ? exe : "\"" + exe + "\"");
            else registry.delete(name);
        } catch (IOException e) {
            LOG.warning("Não consegui atualizar o abrir com o sistema: " + e.getMessage());
        }
    }

    /** Sincroniza agora e a cada save que mudar startWithWindows. */
    SettingsStore wrap(SettingsStore store) {
        sync(store.load().startWithWindows());
        return new SettingsStore() {
            public Settings load() {
                return store.load();
            }

            public void save(Settings s) {
                boolean before = store.load().startWithWindows();
                store.save(s);
                if (s.startWithWindows() != before) sync(s.startWithWindows());
            }
        };
    }

    /** reg.exe, sem dependência nativa. */
    static final class RegExe implements Registry {
        public void set(String name, String value) throws IOException {
            // \" porque o ProcessBuilder não escapa aspas internas: o reg.exe recebe "C:\...\Arara.exe" com aspas
            if (run("add", RUN_KEY, "/v", name, "/t", "REG_SZ", "/d", value.replace("\"", "\\\""), "/f") != 0)
                throw new IOException("reg add falhou");
        }

        public void delete(String name) throws IOException {
            run("delete", RUN_KEY, "/v", name, "/f"); // código 1 = valor já não existe, tudo bem
        }

        private static int run(String... args) throws IOException {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "reg.exe";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    /** Um &lt;name&gt;.plist por app; o launchd o lê no próximo início de sessão (RunAtLoad). */
    static final class LaunchAgents implements Registry {
        static final String LABEL = "br.arara.Arara";
        private final Path dir;

        LaunchAgents(Path dir) {
            this.dir = dir;
        }

        Path file(String name) {
            return dir.resolve(name + ".plist");
        }

        public void set(String name, String exe) throws IOException {
            Files.createDirectories(dir);
            Files.writeString(file(name), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                      <key>Label</key><string>%s</string>
                      <key>ProgramArguments</key><array><string>%s</string></array>
                      <key>RunAtLoad</key><true/>
                      <key>ProcessType</key><string>Interactive</string>
                    </dict>
                    </plist>
                    """.formatted(xml(name), xml(exe)), StandardCharsets.UTF_8);
        }

        public void delete(String name) throws IOException {
            Files.deleteIfExists(file(name));
        }

        private static String xml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
