package br.arara.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * "Iniciar com o Windows": valor Arara em HKCU\...\Run com o caminho do Arara.exe. Só grava no app empacotado
 * (jpackage); rodando pelo Gradle ou java.exe não toca no registro, para não registrar o java.exe.
 */
final class WindowsStartup {
    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final Logger LOG = Logger.getLogger(WindowsStartup.class.getName());

    interface Registry {
        void set(String name, String value) throws IOException;

        void delete(String name) throws IOException;
    }

    private final Registry registry;
    private final String name;
    private final Optional<String> command;

    WindowsStartup(Registry registry, String name, Optional<String> command) {
        this.registry = registry;
        this.name = name;
        this.command = command;
    }

    static WindowsStartup system() {
        return new WindowsStartup(new RegExe(), "Arara", ProcessHandle.current().info().command());
    }

    /** Liga ou desliga; ligado, regrava o caminho atual (corrige se o app mudou de pasta). */
    void sync(boolean on) {
        String exe = command.orElse("");
        Path file = exe.isEmpty() ? null : Path.of(exe).getFileName();
        if (file == null || !file.toString().equalsIgnoreCase("Arara.exe")) {
            LOG.info("Iniciar com o Windows (" + on + ") ignorado fora do app instalado: " + exe);
            return;
        }
        try {
            if (on) registry.set(name, "\"" + exe + "\"");
            else registry.delete(name);
        } catch (IOException e) {
            LOG.warning("Não consegui atualizar o Iniciar com o Windows: " + e.getMessage());
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
}
