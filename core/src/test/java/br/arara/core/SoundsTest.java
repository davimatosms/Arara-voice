package br.arara.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundsTest {
    @Test
    void tamanhoPorDuracaoEPicoNoVolume() {
        for (Settings.Sound k : Settings.Sound.values()) {
            for (Settings.SoundLength l : Settings.SoundLength.values()) {
                for (boolean start : new boolean[] {true, false}) {
                    Settings s = Settings.defaults().withSound(k).withSoundLength(l).withSoundVolume(40);
                    short[] pcm = Sounds.synth(s, start);
                    assertEquals(Sounds.millis(s) * 44.1, pcm.length, 5, k + " " + l);
                    int peak = 0;
                    for (short v : pcm) peak = Math.max(peak, Math.abs(v));
                    assertTrue(peak > 0 && peak <= 0.4 * 32767 + 1, "pico " + peak);
                }
            }
        }
    }
}
