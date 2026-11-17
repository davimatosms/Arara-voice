package br.arara.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** macOS: onde o Arara.app está rodando, aviso de pasta errada e reabrir o próprio app (permissão só vale ao reabrir). */
final class MacApp {
    static final String BUNDLE_ID = "br.arara.Arara";

    private MacApp() {}

    /** Caminho do Arara.app a partir do executável (…/Arara.app/Contents/MacOS/Arara); vazio se não é um .app. */
    static Optional<String> bundle(String command) {
        if (command == null) return Optional.empty();
        int i = command.indexOf(".app/Contents/");
        return i < 0 ? Optional.empty() : Optional.of(command.substring(0, i + 4));
    }

    /** Rodando do DMG ou de caminho isolado pelo Gatekeeper: a permissão não gruda e o caminho muda a cada abertura. */
    static boolean wrongPlace(String path) {
        return path != null && (path.startsWith("/Volumes/") || path.contains("/AppTranslocation/"));
    }

    static String command() {
        return ProcessHandle.current().info().command().orElse(null);
    }

    /** Texto de ajuda do pedido de Acessibilidade, com o aviso de pasta quando for o caso. */
    static String accessHelp() {
        String text = "Para o atalho e o colar funcionarem, ligue o Arara em\n"
                + "Ajustes do Sistema › Privacidade e Segurança › Acessibilidade e clique em Reabrir o Arara.\n\n"
                + "Depois de atualizar, a permissão antiga pode continuar ligada e não valer.\n"
                + "Clique em Zerar e reabrir (ou, no Terminal: tccutil reset Accessibility " + BUNDLE_ID + ") e ligue de novo.";
        if (wrongPlace(command()))
            text = "Mova o Arara para a pasta Aplicativos e abra de lá. Rodando daqui a permissão não vale.\n\n" + text;
        return text;
    }

    /** Comando que apaga a permissão de Acessibilidade guardada (a assinatura ad-hoc muda a cada versão). Sem shell. */
    static List<String> resetCommand() {
        return List.of("/usr/bin/tccutil", "reset", "Accessibility", BUNDLE_ID);
    }

    /** Zera a Acessibilidade do Arara; true se o tccutil terminou bem em até 5 s. Nunca lança. */
    static boolean resetAccess() {
        try {
            // Saída descartada, não fechada: com o pipe fechado o tccutil morre de SIGPIPE ao escrever (saída 141).
            Process p = new ProcessBuilder(resetCommand()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(5, TimeUnit.SECONDS)) return p.exitValue() == 0;
            p.destroyForcibly();
        } catch (Exception ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Aviso para quando o tccutil falha: o caminho manual, pelo Terminal. */
    static String resetFailedHelp() {
        return "Não consegui zerar a permissão. No Terminal, rode:\n\n"
                + "tccutil reset Accessibility " + BUNDLE_ID + "\n\nDepois reabra o Arara e ligue de novo em Acessibilidade.";
    }

    /** Abre uma nova instância do .app e sai. A nova espera o lock de instância única? Sai antes de ela subir, por isso o atraso. */
    static void relaunch() {
        bundle(command()).ifPresent(app -> {
            try {
                new ProcessBuilder("/bin/sh", "-c", "sleep 1; open -n \"$0\"", app).start();
            } catch (Exception ignored) {
                return;
            }
            System.exit(0);
        });
    }
}
