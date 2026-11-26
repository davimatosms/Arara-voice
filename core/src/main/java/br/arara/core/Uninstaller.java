package br.arara.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * "Desinstalar o Arara". Sempre desliga o abrir com o sistema; apaga a pasta de dados só se pedido (e só com as
 * salvaguardas abaixo); depois cuida do app conforme o sistema: Windows abre o unins000.exe do Inno e sai, macOS
 * move o Arara.app para a Lixeira, Linux apaga o AppImage. Dependências injetadas para testar sem tocar no PC.
 */
public final class Uninstaller {
    public enum Os { WINDOWS, MAC, LINUX }

    /** Próximo passo da tela: sair já, mostrar a despedida ou mostrar o texto (nada foi removido do app). */
    public enum Step { EXIT, FAREWELL, MESSAGE }

    /** {@code message}: texto gentil para mostrar (aviso de falha parcial ou instrução manual); pode ser null. */
    public record Outcome(Step step, String message) {}

    /** Inicia um processo (o unins000.exe) sem esperar. */
    public interface Runner {
        void start(List<String> command) throws IOException;
    }

    /** Move para a Lixeira; false se não deu. */
    public interface Trash {
        boolean move(Path path);
    }

    private final Os os;
    private final Path dataDir;
    private final List<Path> roots;
    private final Runnable disableStartup;
    private final Runner runner;
    private final Trash trash;
    private final Runnable release;
    private final Path exe;
    private final Path appImage;

    /**
     * @param roots    pastas em que dataDir tem de estar (home, APPDATA, XDG_DATA_HOME)
     * @param release  solta o lock de instância única e o atalho global antes de apagar ou mover algo
     * @param exe      executável do processo atual (Arara.exe, .../Arara.app/Contents/MacOS/Arara) ou null
     * @param appImage valor de $APPIMAGE ou null
     */
    public Uninstaller(Os os, Path dataDir, List<Path> roots, Runnable disableStartup, Runner runner, Trash trash,
            Runnable release, Path exe, Path appImage) {
        this.os = os;
        this.dataDir = dataDir;
        this.roots = roots;
        this.disableStartup = disableStartup;
        this.runner = runner;
        this.trash = trash;
        this.release = release;
        this.exe = exe;
        this.appImage = appImage;
    }

    /** Montagem real, para o app. */
    public static Uninstaller system(Runnable release) {
        Os os = AraraCore.MAC ? Os.MAC : AraraCore.LINUX ? Os.LINUX : Os.WINDOWS;
        List<Path> roots = new ArrayList<>();
        for (String v : new String[] {System.getProperty("user.home"), System.getenv("APPDATA"), System.getenv("XDG_DATA_HOME")})
            if (v != null && !v.isBlank()) roots.add(Path.of(v));
        Path exe = ProcessHandle.current().info().command().map(Path::of).orElse(null);
        String ai = System.getenv("APPIMAGE");
        Runnable disable = () -> {
            Startup.system().disable();
            LinuxShortcut shortcut = LinuxShortcut.system(AraraCore.defaultDataDir(), false); // GNOME/Wayland: tira o atalho do sistema
            if (shortcut != null) shortcut.remove();
        };
        return new Uninstaller(os, AraraCore.defaultDataDir(), roots, disable,
                c -> new ProcessBuilder(c).start(), Uninstaller::systemTrash, release,
                exe, ai == null || ai.isBlank() ? null : Path.of(ai));
    }

    private static boolean systemTrash(Path p) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH))
                return java.awt.Desktop.getDesktop().moveToTrash(p.toFile());
            Process pr = new ProcessBuilder("osascript", "-e",
                    "tell application \"Finder\" to delete POSIX file \"" + p.toString().replace("\"", "") + "\"").start();
            return pr.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Tamanho da pasta de dados em bytes (sem seguir links); vazio se falhar. Pode demorar: chame fora da EDT. */
    public OptionalLong dataSize() {
        long[] total = {0};
        try {
            if (!Files.exists(dataDir, LinkOption.NOFOLLOW_LINKS)) return OptionalLong.of(0);
            Files.walkFileTree(dataDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile()) total[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return OptionalLong.of(total[0]);
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /** A pasta se chama Arara, não é um link e está dentro (não igual) de uma das raízes esperadas. */
    boolean safeDataDir() {
        try {
            if (dataDir.getFileName() == null || !dataDir.getFileName().toString().equals("Arara")) return false;
            if (Files.isSymbolicLink(dataDir)) return false;
            Path parent = dataDir.toAbsolutePath().getParent();
            if (parent == null || !Files.isDirectory(parent)) return false;
            Path real = parent.toRealPath();
            for (Path r : roots) {
                if (Files.isDirectory(r) && real.startsWith(r.toRealPath())) return true;
            }
        } catch (IOException | RuntimeException e) {
            // sem como provar que é seguro: não apaga
        }
        return false;
    }

    /** Apaga só dataDir. Retorna null se tudo certo ou o aviso gentil. */
    private String deleteData() {
        if (!safeDataDir()) return "Não apaguei seus dados: a pasta não é a esperada (" + dataDir + ").";
        if (!Files.exists(dataDir, LinkOption.NOFOLLOW_LINKS)) return null;
        int[] failed = {0};
        try {
            Files.walkFileTree(dataDir, new SimpleFileVisitor<>() { // links viram arquivos: apaga o link, nunca o alvo
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    try {
                        Files.delete(f);
                    } catch (IOException e) {
                        failed[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    failed[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    try {
                        Files.delete(d);
                    } catch (IOException ex) {
                        failed[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failed[0]++;
        }
        return failed[0] == 0 ? null
                : "Alguns arquivos não saíram (talvez estejam em uso). Você pode apagar a pasta " + dataDir + " depois.";
    }

    public Outcome run(boolean deleteData) {
        disableStartup.run();
        release.run(); // no Windows o lock fica dentro da pasta de dados
        String warn = deleteData ? deleteData() : null;
        Outcome o = switch (os) {
            case WINDOWS -> windows();
            case MAC -> mac();
            case LINUX -> linux();
        };
        String msg = warn == null ? o.message() : o.message() == null ? warn : warn + "\n\n" + o.message();
        return new Outcome(o.step(), msg);
    }

    private Outcome windows() {
        Path unins = exe == null || exe.getParent() == null ? null : exe.getParent().resolve("unins000.exe");
        if (unins == null || !exe.getFileName().toString().equalsIgnoreCase("Arara.exe") || !Files.isRegularFile(unins))
            return new Outcome(Step.MESSAGE, "Abra Configurações > Aplicativos > Arara > Desinstalar.");
        try {
            runner.start(List.of(unins.toString()));
            return new Outcome(Step.EXIT, null);
        } catch (IOException e) {
            return new Outcome(Step.MESSAGE, "Não consegui abrir o desinstalador. Abra Configurações > Aplicativos > Arara > Desinstalar.");
        }
    }

    private Outcome mac() {
        String s = exe == null ? "" : exe.toString().replace('\\', '/');
        if (!s.endsWith(".app/Contents/MacOS/Arara"))
            return new Outcome(Step.MESSAGE, "O Arara não está rodando como app instalado, então não movi nada.");
        Path bundle = exe.getParent().getParent().getParent();
        return trash.move(bundle) ? new Outcome(Step.FAREWELL, null)
                : new Outcome(Step.MESSAGE, "Não consegui mover o Arara para a Lixeira. Arraste o Arara.app para lá você mesmo.");
    }

    private Outcome linux() {
        if (appImage != null && Files.isRegularFile(appImage) && Files.isWritable(appImage)) {
            try {
                Files.delete(appImage);
                return new Outcome(Step.FAREWELL, null);
            } catch (IOException e) {
                // cai na mensagem
            }
        }
        return new Outcome(Step.MESSAGE, "Não achei o AppImage para apagar. Apague o arquivo do Arara você mesmo.");
    }
}
