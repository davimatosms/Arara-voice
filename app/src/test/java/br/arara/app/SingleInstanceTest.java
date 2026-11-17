package br.arara.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Só pastas temporárias: nunca o %APPDATA%\Arara de verdade. */
class SingleInstanceTest {
    @Test
    void segundoLockFalhaAteOPrimeiroSoltar(@TempDir Path dir) throws Exception {
        SingleInstance first = SingleInstance.acquire(dir);
        assertNotNull(first);
        assertNull(SingleInstance.acquire(dir));
        CountDownLatch shown = new CountDownLatch(1);
        first.onShow = shown::countDown;
        assertTrue(SingleInstance.notifyFirst(dir));
        assertTrue(shown.await(5, TimeUnit.SECONDS));
        first.close();
        SingleInstance again = SingleInstance.acquire(dir);
        assertNotNull(again);
        again.close();
    }

    @Test
    void alternarChegaNaPrimeira(@TempDir Path dir) throws Exception {
        try (SingleInstance first = SingleInstance.acquire(dir)) {
            CountDownLatch toggled = new CountDownLatch(1);
            CountDownLatch shown = new CountDownLatch(1);
            first.onToggle = toggled::countDown;
            first.onShow = shown::countDown;
            assertTrue(SingleInstance.notifyFirst(dir, SingleInstance.TOGGLE));
            assertTrue(toggled.await(5, TimeUnit.SECONDS));
            assertEquals(1, shown.getCount()); // alternar não mostra a janela
        }
    }

    @Test
    void avisoSemNinguemOuvindoFalha(@TempDir Path dir) {
        assertFalse(SingleInstance.notifyFirst(dir));
    }

    /** Dois processos abertos no mesmo instante, como o "Iniciar com o Windows" e um clique: só um fica. */
    @Test
    void doisProcessosSoUmFicaDePe(@TempDir Path dir) throws Exception {
        List<Process> procs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) procs.add(probe(dir));
            List<BufferedReader> outs = new ArrayList<>();
            List<String> first = new ArrayList<>();
            for (Process p : procs) {
                BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                outs.add(out);
                first.add(out.readLine());
            }
            int winner = first.indexOf("PRIMEIRA");
            assertTrue(winner >= 0, "nenhum ficou de pé: " + first);
            Process loser = procs.get(1 - winner);
            assertEquals("AVISOU", first.get(1 - winner));
            assertTrue(loser.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, loser.exitValue());
            assertEquals("MOSTRAR", outs.get(winner).readLine()); // a primeira recebeu o pedido de mostrar a janela
            assertTrue(procs.get(winner).isAlive());
        } finally {
            for (Process p : procs) p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static Process probe(Path dir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), dir.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    /** O mesmo começo do Main, sem janela nem atalho. */
    static final class Probe {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]);
            SingleInstance instance = SingleInstance.acquire(dir);
            if (instance == null) {
                System.out.println(SingleInstance.notifyFirst(dir) ? "AVISOU" : "NAO_AVISOU");
                System.exit(0);
            }
            instance.onShow = () -> System.out.println("MOSTRAR");
            System.out.println("PRIMEIRA");
            Thread.sleep(60_000); // o teste mata antes
        }
    }
}
