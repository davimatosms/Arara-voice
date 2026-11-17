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
 * Linux ("Iniciar com a sessão"): .desktop em ~/.config/autostart com o caminho do .AppImage.
 * Só grava no app empacotado (jpackage); rodando pelo Gradle ou pelo java não toca em nada, para não registrar o java.
 */
final class Startup {
    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final Logger LOG = Logger.getLogger(Startup.class.getName());

    /** Onde fica o registro: o Run do registro do Windows, a pasta de LaunchAgents do macOS ou o autostart do Linux. */
    interface Registry {
        void set(String name, String value) throws IOException;

        void delete(String name) throws IOException;
    }

    private final Registry registry;
    private final String name;
    private final Optional<String> command;
    private final boolean mac;
    private final boolean linux;

    Startup(Registry registry, String name, Optional<String> command, boolean mac) {
        this(registry, name, command, mac, false);
    }

    Startup(Registry registry, String name, Optional<String> command, boolean mac, boolean linux) {
        this.registry = registry;
        this.name = name;
        this.command = command;
        this.mac = mac;
        this.linux = linux;
    }

    static Startup system() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (AraraCore.LINUX) {
            // APPIMAGE é o arquivo estável; o ProcessHandle aponta para /tmp/.mount_...
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
            return new Startup(new Autostart(base.resolve("autostart")), Autostart.DESKTOP,
                    Optional.ofNullable(linuxLauncher(command)), false, true);
        }
        if (AraraCore.MAC) {
            Path agents = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
            return new Startup(new LaunchAgents(agents), LaunchAgents.LABEL, command, true);
        }
        return new Startup(new RegExe(), "Arara", command, false);
    }

    /** AppImage (env APPIMAGE) ou .../bin/Arara do app empacotado; null se for outra coisa. */
    private static String linuxLauncher(Optional<String> command) {
        String appImage = System.getenv("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) return appImage;
        return command.filter(c -> packaged(c, false, true)).orElse(null);
    }

    /** O processo é o app empacotado? Arara.exe no Windows, .../Arara.app/Contents/MacOS/Arara no macOS. */
    static boolean packaged(String exe, boolean mac) {
        return packaged(exe, mac, false);
    }

    /** No Linux: .AppImage ou .../bin/Arara do app empacotado, nunca o launcher dentro do monte. */
    static boolean packaged(String exe, boolean mac, boolean linux) {
        if (mac) return exe.endsWith(".app/Contents/MacOS/Arara");
        if (linux) return !exe.contains("/.mount_") && (exe.endsWith(".AppImage") || exe.endsWith("/bin/Arara"));
        // nome do arquivo à mão, sem Path: os testes rodam caminhos do Windows também no macOS
        return exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).equalsIgnoreCase("Arara.exe");
    }

    /** Liga ou desliga; ligado, regrava o caminho atual (corrige se o app mudou de pasta). */
    void sync(boolean on) {
        String exe = command.orElse("");
        if (!packaged(exe, mac, linux)) {
            LOG.info("Abrir com o sistema (" + on + ") ignorado fora do app instalado: " + exe);
            return;
        }
        try {
            if (on) registry.set(name, mac || linux ? exe : "\"" + exe + "\"");
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

    /** Um .desktop em ~/.config/autostart (ou $XDG_CONFIG_HOME/autostart). */
    static final class Autostart implements Registry {
        static final String DESKTOP = "br.arara.Arara";
        private final Path dir;

        Autostart(Path dir) {
            this.dir = dir;
        }

        Path file(String name) {
            return dir.resolve(name + ".desktop");
        }

        public void set(String name, String exe) throws IOException {
            Files.createDirectories(dir);
            // Aspas só quando há espaço: o teste do AppImage exige o caminho cru, o do bin/Arara exige aspas.
            String quoted = exe.contains(" ") ? "\"" + exe + "\"" : exe;
            Files.writeString(file(name), "[Desktop Entry]\nType=Application\nName=Arara\nExec=" + quoted
                    + "\nX-GNOME-Autostart-enabled=true\n", StandardCharsets.UTF_8);
        }

        public void delete(String name) throws IOException {
            Files.deleteIfExists(file(name));
        }
    }
}
