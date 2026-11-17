package br.arara.core;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Aperta Ctrl+V no Wayland, onde o Robot não alcança apps nativos. Tenta, na ordem: wtype (wlroots/KDE), ydotool
 * (precisa do ydotoold) e o portal xdg RemoteDesktop (GNOME; pede permissão na primeira vez e guarda o token).
 * Sem lib D-Bus: o portal é falado por um script curto de python3 + GDBus (já presentes no GNOME), sem dependência
 * nova nem problema de licença.
 */
final class LinuxPaste {
    record Out(int exit, String out) {}

    /** Ambiente injetável para testar a escolha sem Wayland de verdade. */
    interface Env {
        boolean has(String tool);

        boolean ydotoolSocket();

        /** Roda sem shell, com timeout; exit -1 se não iniciou ou estourou o tempo. */
        Out run(int timeoutMs, String stdin, String... cmd);

        String token();

        void saveToken(String token);
    }

    /** Lançada quando nenhuma estratégia colou: o texto ficou no clipboard e o usuário precisa colar com Ctrl+V. */
    static final class CopiedOnly extends Exception {
        CopiedOnly() {
            super("Texto copiado: cole com Ctrl+V.");
        }
    }

    // shortcut: detecção local; trocar por AraraCore.wayland() quando o PR de Linux entrar.
    static boolean wayland(String os, String sessionType, String waylandDisplay) {
        if (!os.toLowerCase().contains("linux")) return false;
        return "wayland".equalsIgnoreCase(sessionType) || (waylandDisplay != null && !waylandDisplay.isBlank());
    }

    static boolean wayland() {
        return wayland(System.getProperty("os.name", ""), System.getenv("XDG_SESSION_TYPE"), System.getenv("WAYLAND_DISPLAY"));
    }

    /** Aperta Ctrl+V e devolve o nome da estratégia que funcionou, ou null. */
    static String press(Env env) {
        if (env.has("wtype") && env.run(3000, null, "wtype", "-M", "ctrl", "v", "-m", "ctrl").exit() == 0) return "wtype";
        if (env.has("ydotool") && env.ydotoolSocket()
                && env.run(3000, null, "ydotool", "key", "29:1", "47:1", "47:0", "29:0").exit() == 0) return "ydotool";
        if (env.has("python3") && portal(env)) return "portal";
        return null;
    }

    private static boolean portal(Env env) {
        String token = env.token();
        for (int tries = 0; tries < 2; tries++) {
            Out r = env.run(70_000, null, "python3", "-c", PORTAL_SCRIPT, token);
            if (r.exit() == 0) {
                for (String line : r.out().split("\\R")) {
                    if (line.startsWith("TOKEN=") && !line.substring(6).equals(token)) env.saveToken(line.substring(6));
                }
                return true;
            }
            if (token.isEmpty()) return false;
            token = ""; // token recusado/expirado: pede a permissão de novo uma vez
            env.saveToken("");
        }
        return false;
    }

    /** Copia o texto com wl-copy quando existe (o clipboard do Java só chega aos apps via XWayland). */
    static void wlCopy(Env env, String text) {
        if (env.has("wl-copy")) env.run(2000, text, "wl-copy");
    }

    // Fluxo do portal: CreateSession, SelectDevices(teclado, persist 2, restore_token), Start, teclas 29 (Ctrl) e 47 (V).
    // Sai com 0 e imprime TOKEN=...; 2 se o usuário negou, 3 se não respondeu a tempo, 4 sem GDBus.
    private static final String PORTAL_SCRIPT = """
            import sys, time
            try:
                import gi
                gi.require_version('Gio', '2.0')
                from gi.repository import Gio, GLib
            except Exception:
                sys.exit(4)
            tok = sys.argv[1] if len(sys.argv) > 1 else ''
            B = 'org.freedesktop.portal.Desktop'; P = '/org/freedesktop/portal/desktop'; RD = 'org.freedesktop.portal.RemoteDesktop'
            bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            me = bus.get_unique_name()[1:].replace('.', '_')
            loop = GLib.MainLoop(); n = [0]
            def V(t, v): return GLib.Variant(t, v)
            def req(method, build, wait):
                n[0] += 1; t = 'arara%d' % n[0]
                path = '/org/freedesktop/portal/desktop/request/%s/%s' % (me, t)
                got = []
                def cb(c, s, p, i, sg, prm):
                    got.append(prm.unpack()); loop.quit()
                sub = bus.signal_subscribe(B, 'org.freedesktop.portal.Request', 'Response', path, None, 0, cb)
                opts = {'handle_token': V('s', t)}
                fmt, args = build(opts)
                bus.call_sync(B, P, RD, method, V(fmt, args), None, 0, -1, None)
                if not got:
                    GLib.timeout_add(wait * 1000, loop.quit); loop.run()
                bus.signal_unsubscribe(sub)
                if not got: sys.exit(3)
                if got[0][0] != 0: sys.exit(2)
                return got[0][1]
            def cs(o):
                o['session_handle_token'] = V('s', 'arara'); return '(a{sv})', (o,)
            sess = req('CreateSession', cs, 15)['session_handle']
            def sd(o):
                o['types'] = V('u', 1); o['persist_mode'] = V('u', 2)
                if tok: o['restore_token'] = V('s', tok)
                return '(oa{sv})', (sess, o)
            req('SelectDevices', sd, 15)
            res = req('Start', lambda o: ('(osa{sv})', (sess, '', o)), 60)
            def key(code, st):
                bus.call_sync(B, P, RD, 'NotifyKeyboardKeycode', V('(oa{sv}iu)', (sess, {}, code, st)), None, 0, -1, None)
                time.sleep(0.02)
            key(29, 1); key(47, 1); key(47, 0); key(29, 0)
            bus.call_sync(B, sess, 'org.freedesktop.portal.Session', 'Close', None, None, 0, -1, None)
            if res.get('restore_token'): print('TOKEN=' + res['restore_token'])
            """;

    /** Ambiente real: PATH, processos sem shell e token em portal-token na pasta de dados. */
    static final class SystemEnv implements Env {
        private final Path tokenFile = AraraCore.defaultDataDir().resolve("portal-token");

        @Override
        public boolean has(String tool) {
            String path = System.getenv("PATH");
            if (path == null) return false;
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, tool))) return true;
            }
            return false;
        }

        @Override
        public boolean ydotoolSocket() {
            String env = System.getenv("YDOTOOL_SOCKET"), run = System.getenv("XDG_RUNTIME_DIR");
            for (String s : new String[] {env, run == null ? null : run + "/.ydotool_socket", "/tmp/.ydotool_socket"}) {
                if (s != null && Files.isWritable(Path.of(s))) return true;
            }
            return false;
        }

        @Override
        public Out run(int timeoutMs, String stdin, String... cmd) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                // wl-copy deixa um filho vivo segurando o pipe: com stdin não lemos a saída.
                if (stdin != null) pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                if (stdin != null) p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    return new Out(-1, "");
                }
                String out = stdin != null ? "" : new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new Out(p.exitValue(), out);
            } catch (IOException e) {
                return new Out(-1, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Out(-1, "");
            }
        }

        @Override
        public String token() {
            try {
                return Files.exists(tokenFile) ? Files.readString(tokenFile).trim() : "";
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public void saveToken(String token) {
            try {
                Files.writeString(tokenFile, token);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
