package br.arara.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Aceleração por GPU do Whisper (Metal no Mac). O backend pode dar abort() nativo, que Java não captura: por isso
 * um marcador no disco vale como "travou aqui" e a próxima partida volta para o processador.
 */
final class Gpu {
    static final String MARKER = "gpu-em-teste";
    static final String NOTICE = "A aceleração por GPU travou neste Mac. O Arara passou a usar o processador.";

    private Gpu() {}

    /** Mac com GPU: só se o usuário não escolheu "só processador" (CPU antigo = automático, exceto no M1). */
    static boolean enabled(Settings s, boolean mac, boolean appleM1) {
        return mac && s.accel() != Settings.Accel.CPU_ONLY && !(s.accel() == Settings.Accel.CPU && appleM1);
    }

    static boolean enabled(Settings s) {
        return enabled(s, AraraCore.MAC, AraraCore.MAC && appleM1());
    }

    /** Família Apple7 (M1), onde o Metal do whisper.cpp 1.7.1 aborta. */
    static boolean appleM1() {
        String model = sysctl("hw.model"), brand = sysctl("machdep.cpu.brand_string");
        return isM1(model, brand);
    }

    static boolean isM1(String model, String brand) {
        for (String p : new String[] {"MacBookAir10,", "MacBookPro17,", "Macmini9,", "iMac21,"}) {
            if (model.startsWith(p)) return true;
        }
        return brand.matches("Apple M1( .*)?");
    }

    private static String sysctl(String key) {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * Na partida: marcador presente = o processo morreu no meio de uma transcrição com GPU. Passa para só
     * processador, apaga o marcador e devolve o aviso (ou null).
     */
    static String recover(Path dataDir, SettingsStore settings) {
        Path marker = dataDir.resolve(MARKER);
        if (!Files.exists(marker)) return null;
        settings.save(settings.load().withAccel(Settings.Accel.CPU_ONLY));
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // sem apagar, na próxima partida só repete o aviso
        }
        return NOTICE;
    }
}
