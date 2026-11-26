package br.arara.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atalho do sistema no GNOME/Wayland, criado pelo próprio Arara. O Wayland não deixa um app ouvir teclas fora da
 * própria janela, então o JNativeHook só vê o atalho com uma janela XWayland em foco. Aqui o atalho escolhido nas
 * configurações vira um atalho personalizado do GNOME (o mesmo de Configurações › Teclado), que chama um script
 * curto: ele fala com o Arara aberto pela porta local de instância única, sem subir outra JVM.
 *
 * <p>O Esc entra do mesmo jeito, mas só enquanto há ditado em andamento: fora disso o atalho fica sem tecla e o Esc
 * segue normal para os outros apps. Se o Arara morrer com o Esc preso, o script solta a tecla no primeiro aperto.
 *
 * <p>Sem lib D-Bus: tudo pelo {@code gsettings}, que já vem com o GNOME.
 */
final class LinuxShortcut {
    /** Roda {@code gsettings <args>}; devolve a saída, ou null se falhou. Injetável para testar sem GNOME. */
    interface Gs {
        String run(String... args);
    }

    static final String SCHEMA = "org.gnome.settings-daemon.plugins.media-keys";
    static final String LIST = "custom-keybindings";
    static final String BASE = "/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/";
    static final String TOGGLE = BASE + "arara/";
    static final String ESC = BASE + "arara-esc/";
    static final String SCRIPT = "arara-atalho";

    /** O atalho de ditar está registrado e funcionando (tela de configurações e primeira execução). */
    static volatile boolean active;

    private final Gs gs;
    private final Path dataDir;
    private final Executor worker; // grava o Esc fora da thread do motor; o teste roda na hora
    /**
     * O Arara já lê o teclado sozinho ({@link LinuxEvdev}): o atalho de ditar fica só para a tecla não vazar para o
     * app em foco, e o comando dele não faz nada (senão cada aperto contaria duas vezes). O do Esc continua
     * chamando o script: cancelar duas vezes não muda nada, e é o script que solta o Esc se o Arara morrer.
     */
    private final boolean swallowOnly;
    private boolean ready; // os dois atalhos existem na lista do GNOME
    private boolean escOn; // só a thread do worker mexe

    LinuxShortcut(Gs gs, Path dataDir, boolean swallowOnly, Executor worker) {
        this.gs = gs;
        this.dataDir = dataDir;
        this.swallowOnly = swallowOnly;
        this.worker = worker;
    }

    /** Sessão em que isto vale: Wayland + GNOME (as distros se anunciam como "nome:GNOME", por exemplo "ubuntu:GNOME"). */
    static boolean applies(boolean wayland, Function<String, String> env) {
        String d = env.apply("XDG_CURRENT_DESKTOP");
        return wayland && d != null && d.toUpperCase(Locale.ROOT).contains("GNOME");
    }

    /**
     * null fora do GNOME/Wayland ou sem o esquema de atalhos personalizados.
     *
     * @param swallowOnly o teclado já é lido pelo {@link LinuxEvdev}; o atalho só segura a tecla
     */
    static LinuxShortcut system(Path dataDir, boolean swallowOnly) {
        if (!applies(AraraCore.wayland(), System::getenv)) return null;
        Gs gs = LinuxShortcut::gsettings;
        if (gs.run("get", SCHEMA, LIST) == null) return null;
        return new LinuxShortcut(gs, dataDir, swallowOnly, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "arara-atalho-sistema");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * "Ctrl+Shift+Space" -> {@code <Control><Shift>space}. null se o GNOME não tem como registrar: botão do mouse,
     * modificador sozinho ou tecla que não conhecemos pelo nome.
     */
    static String accelerator(String hotkey) {
        if (hotkey == null) return null;
        StringBuilder mods = new StringBuilder();
        String key = null;
        for (String part : hotkey.split("\\+")) {
            String p = part.strip().toUpperCase(Locale.ROOT).replace(" ", "").replace("_", "");
            switch (p) {
                case "CTRL", "CONTROL" -> mods.append("<Control>");
                case "SHIFT" -> mods.append("<Shift>");
                case "ALT", "OPTION" -> mods.append("<Alt>");
                case "WIN", "META", "CMD" -> mods.append("<Super>");
                default -> {
                    if (key != null) return null;
                    key = keysym(p);
                    if (key == null) return null;
                }
            }
        }
        return key == null ? null : mods + key;
    }

    private static String keysym(String p) {
        if (p.matches("[A-Z]")) return p.toLowerCase(Locale.ROOT);
        if (p.matches("[0-9]") || p.matches("F([1-9]|1[0-9]|2[0-4])")) return p;
        return switch (p) {
            case "SPACE" -> "space";
            case "ESC", "ESCAPE" -> "Escape";
            case "TAB" -> "Tab";
            case "ENTER" -> "Return";
            case "BACKSPACE" -> "BackSpace";
            case "INSERT" -> "Insert";
            case "DELETE" -> "Delete";
            case "HOME" -> "Home";
            case "END" -> "End";
            case "PAGEUP" -> "Page_Up";
            case "PAGEDOWN" -> "Page_Down";
            case "UP" -> "Up";
            case "DOWN" -> "Down";
            case "LEFT" -> "Left";
            case "RIGHT" -> "Right";
            case "PAUSE" -> "Pause";
            case "SCROLLLOCK" -> "Scroll_Lock";
            case "PRINTSCREEN" -> "Print";
            case "MINUS" -> "minus";
            case "EQUALS" -> "equal";
            case "COMMA" -> "comma";
            case "PERIOD" -> "period";
            case "SLASH" -> "slash";
            case "BACKSLASH" -> "backslash";
            case "SEMICOLON" -> "semicolon";
            case "QUOTE" -> "apostrophe";
            case "BACKQUOTE" -> "grave";
            case "OPENBRACKET" -> "bracketleft";
            case "CLOSEBRACKET" -> "bracketright";
            default -> null; // MOUSE3..5, RCTRL e afins caem aqui
        };
    }

    /** Lista do gsettings ({@code ['a', 'b']} ou {@code @as []}) em caminhos. */
    static List<String> paths(String gvariant) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("'([^']*)'").matcher(gvariant == null ? "" : gvariant);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String list(List<String> paths) {
        StringBuilder sb = new StringBuilder("[");
        for (String p : paths) sb.append(sb.length() > 1 ? ", " : "").append('\'').append(p).append('\'');
        return sb.append(']').toString();
    }

    /** Texto do GVariant para uma string, com aspas e barras escapadas. */
    private static String str(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private boolean set(String path, String key, String value) {
        return gs.run("set", SCHEMA + ".custom-keybinding:" + path, key, str(value)) != null;
    }

    /** Comando do atalho: o gnome-settings-daemon separa os argumentos como um shell, então o caminho vai entre aspas. */
    private String command(String action) {
        return "'" + dataDir.resolve(SCRIPT).toString().replace("'", "'\\''") + "' " + action;
    }

    /**
     * Deixa o atalho do GNOME igual ao das configurações. Chamado ao abrir e a cada troca de atalho; repetir não
     * muda nada. O Esc entra na lista já sem tecla. Devolve se o atalho de ditar ficou valendo.
     */
    synchronized boolean sync(Settings s) {
        Path script = dataDir.resolve(SCRIPT);
        String current = gs.run("get", SCHEMA, LIST);
        try {
            Files.writeString(script, SCRIPT_TEXT);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException e) {
            current = null;
        }
        if (current == null) return active = false;
        List<String> all = paths(current);
        boolean changed = false;
        for (String p : new String[] {TOGGLE, ESC}) {
            if (!all.contains(p)) {
                all.add(p);
                changed = true;
            }
        }
        String accel = accelerator(s.hotkey());
        boolean ok = set(TOGGLE, "name", "Arara: ditar")
                && set(TOGGLE, "command", swallowOnly ? "true" : command("alternar"))
                && set(TOGGLE, "binding", accel == null ? "" : accel)
                && set(ESC, "name", "Arara: cancelar o ditado (Esc)")
                && set(ESC, "command", command("cancelar"))
                && set(ESC, "binding", "")
                && (!changed || gs.run("set", SCHEMA, LIST, list(all)) != null);
        ready = ok;
        // Modificador sozinho o GNOME não registra: só vale com o evdev lendo o teclado. Botão do mouse, nem assim.
        boolean mouse = s.hotkey().toUpperCase(Locale.ROOT).contains("MOUSE");
        return active = ok && (accel != null || swallowOnly && !mouse);
    }

    /** Prende (ditado em andamento) ou solta o Esc. Não bloqueia: o gsettings roda na thread própria, em ordem. */
    void escape(boolean on) {
        worker.execute(() -> {
            if (!ready || on == escOn) return;
            if (set(ESC, "binding", on ? "Escape" : "")) escOn = on;
        });
    }

    /** Refaz o atalho quando ele muda nas configurações. */
    SettingsStore wrap(SettingsStore store) {
        sync(store.load());
        return new SettingsStore() {
            public Settings load() {
                return store.load();
            }

            public void save(Settings s) {
                String before = store.load().hotkey();
                store.save(s);
                if (!s.hotkey().equals(before)) sync(s);
            }
        };
    }

    /** Esc preso só enquanto há o que cancelar (ouvindo ou transcrevendo) e com "Esc cancela" ligado. */
    DictationListener listener(SettingsStore settings) {
        return new DictationListener() {
            @Override
            public void onState(DictationState s) {
                boolean busy = s == DictationState.LISTENING || s == DictationState.SILENCE || s == DictationState.TRANSCRIBING;
                escape(busy && settings.load().escCancels());
            }
        };
    }

    /** Ao fechar o Arara: solta o Esc e espera o gsettings terminar. O atalho de ditar fica (avisa que o app está fechado). */
    void close() {
        escape(false);
        if (!(worker instanceof ExecutorService service)) return;
        service.shutdown();
        try {
            service.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Desinstalação: tira os dois atalhos do GNOME e apaga o script. */
    synchronized void remove() {
        String current = gs.run("get", SCHEMA, LIST);
        if (current != null) {
            List<String> all = paths(current);
            if (all.remove(TOGGLE) | all.remove(ESC)) gs.run("set", SCHEMA, LIST, list(all));
        }
        for (String p : new String[] {TOGGLE, ESC}) gs.run("reset-recursively", SCHEMA + ".custom-keybinding:" + p);
        try {
            Files.deleteIfExists(dataDir.resolve(SCRIPT));
        } catch (IOException ignored) {
            // some com a pasta de dados, se a pessoa pediu
        }
        ready = false;
        active = false;
    }

    // Mesmo protocolo do SingleInstance (app): lê a saudação "ARARA" e manda uma linha. /dev/tcp é do bash.
    static final String SCRIPT_TEXT = """
            #!/usr/bin/env bash
            # Gerado pelo Arara (não edite: é reescrito ao abrir o app).
            # O atalho do GNOME chama este script, que avisa o Arara aberto pela porta local.
            dir="$(dirname "$(readlink -f "$0")")"
            msg=ALTERNAR
            [ "$1" = cancelar ] && msg=CANCELAR
            port="$(cat "$dir/arara.porta" 2>/dev/null)"
            case "$port" in ''|*[!0-9]*) port= ;; esac
            if [ -n "$port" ] && exec 3<>"/dev/tcp/127.0.0.1/$port"; then
                if IFS= read -r -t 2 hello <&3 && [ "$hello" = ARARA ]; then
                    printf '%%s\\n' "$msg" >&3
                    exit 0
                fi
            fi 2>/dev/null
            if [ "$1" = cancelar ]; then
                # O Arara fechou com o Esc preso: solta a tecla.
                gsettings set org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:%s binding ''
            elif command -v notify-send >/dev/null 2>&1; then
                notify-send -a Arara "Arara" "O Arara não está aberto."
            fi
            exit 1
            """.formatted(ESC);

    private static String gsettings(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("gsettings"));
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
