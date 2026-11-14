package br.arara.core;

/**
 * Detecção de fala por energia, com piso de ruído adaptativo. Conta o tempo pelas amostras recebidas.
 * shortcut: limiares escolhidos sem medir em hardware real; trocar por Silero VAD se errar muito.
 */
final class Vad {
    enum Result { LISTENING, SILENCE, TIMEOUT }

    static final int RATE = 16_000;
    private static final long SILENCE_AFTER = RATE * 5L / 2; // 2,5 s
    private static final long TIMEOUT_AFTER = RATE * 60L * 5; // 5 min
    private static final double MIN_SPEECH_DB = -50;
    private static final double MARGIN_DB = 12;
    static final int SPEECH_CHUNKS = 3; // ~100 ms seguidos: um clique não conta como fala

    private double floorDb = -60;
    private int loudRun;
    private long sinceSpeech;
    private double lastDb = -90;
    private boolean heardSpeech;
    private long fed;
    private long speechStart = -1;
    private long speechEnd;

    Result feed(short[] chunk) {
        fed += chunk.length;
        double db = lastDb = dbfs(chunk);
        boolean loud = db > Math.max(MIN_SPEECH_DB, floorDb + MARGIN_DB);
        // Piso desce na hora e sobe devagar (~16 s), para acompanhar ruído de fundo sem engolir a fala.
        floorDb = db < floorDb ? db : floorDb + 0.002 * (db - floorDb);
        loudRun = loud ? loudRun + 1 : 0;
        if (loudRun >= SPEECH_CHUNKS) {
            heardSpeech = true;
            if (speechStart < 0) speechStart = Math.max(0, fed - (long) SPEECH_CHUNKS * chunk.length);
            speechEnd = fed;
        }
        sinceSpeech = loudRun >= SPEECH_CHUNKS ? 0 : sinceSpeech + chunk.length;
        if (sinceSpeech >= TIMEOUT_AFTER) return Result.TIMEOUT;
        return sinceSpeech >= SILENCE_AFTER ? Result.SILENCE : Result.LISTENING;
    }

    /** Houve fala em algum momento desde o início. */
    boolean heardSpeech() {
        return heardSpeech;
    }

    /** Amostra onde a primeira fala começou, ou -1 se não houve fala. */
    long speechStart() {
        return speechStart;
    }

    /** Amostra logo depois do último bloco com fala. */
    long speechEnd() {
        return speechEnd;
    }

    /** Nível do último bloco para a UI: -60 dBFS = 0, 0 dBFS = 1. */
    float level() {
        return level(lastDb);
    }

    static float level(double db) {
        return (float) Math.min(1, Math.max(0, (db + 60) / 60));
    }

    static double dbfs(short[] chunk) {
        if (chunk.length == 0) return -90;
        double sum = 0;
        for (short s : chunk) sum += (double) s * s;
        double rms = Math.sqrt(sum / chunk.length) / 32768;
        return Math.max(-90, 20 * Math.log10(rms));
    }
}
