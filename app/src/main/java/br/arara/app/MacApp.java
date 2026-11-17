package br.arara.app;

import java.util.Optional;

/** macOS: onde o Arara.app está rodando, aviso de pasta errada e reabrir o próprio app (permissão só vale ao reabrir). */
final class MacApp {
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
                + "Se já está ligado e continua pedindo: selecione o Arara na lista, clique em “−”,\n"
                + "adicione de novo (+) e reabra. Isso acontece depois de atualizar o app.";
        if (wrongPlace(command()))
            text = "Mova o Arara para a pasta Aplicativos e abra de lá. Rodando daqui a permissão não vale.\n\n" + text;
        return text;
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
