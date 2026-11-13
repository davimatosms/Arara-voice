package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VadTest {
    static final int CHUNK = 512;
    static final short[] QUIET = noise(30);    // ~-60 dBFS
    static final short[] SPEECH = noise(3000); // ~-20 dBFS

    static short[] noise(int amplitude) {
        short[] s = new short[CHUNK];
        for (int i = 0; i < s.length; i++) s[i] = (short) (i % 2 == 0 ? amplitude : -amplitude);
        return s;
    }

    static Vad.Result feedSeconds(Vad vad, short[] chunk, double seconds) {
        Vad.Result r = null;
        for (long n = 0; n < seconds * Vad.RATE; n += CHUNK) r = vad.feed(chunk);
        return r;
    }

    @Test
    void silenceAfterTwoAndAHalfSecondsAndBackOnSpeech() {
        Vad vad = new Vad();
        assertEquals(Vad.Result.LISTENING, feedSeconds(vad, QUIET, 2.3));
        assertEquals(Vad.Result.SILENCE, feedSeconds(vad, QUIET, 0.3));
        assertEquals(Vad.Result.LISTENING, feedSeconds(vad, SPEECH, 0.2));
        assertTrue(vad.level() > 0.6f);
    }

    @Test
    void singleClickDoesNotCountAsSpeech() {
        Vad vad = new Vad();
        feedSeconds(vad, QUIET, 3);
        assertEquals(Vad.Result.SILENCE, vad.feed(SPEECH));
        assertEquals(Vad.Result.SILENCE, vad.feed(QUIET));
    }

    @Test
    void timeoutAfterFiveMinutesWithoutSpeech() {
        Vad vad = new Vad();
        assertEquals(Vad.Result.SILENCE, feedSeconds(vad, QUIET, 4 * 60));
        feedSeconds(vad, SPEECH, 0.5); // fala zera a contagem
        assertEquals(Vad.Result.SILENCE, feedSeconds(vad, QUIET, 4 * 60 + 50));
        assertEquals(Vad.Result.TIMEOUT, feedSeconds(vad, QUIET, 15));
    }

    @Test
    void digitalSilenceIsFinite() {
        assertEquals(-90, Vad.dbfs(new short[CHUNK]));
        short[] full = new short[CHUNK];
        Arrays.fill(full, Short.MAX_VALUE);
        assertTrue(Vad.dbfs(full) > -0.01);
    }
}
