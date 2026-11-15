package br.arara.app;

import br.arara.core.DictationState;
import br.arara.core.UpdateChecker.Update;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateUiTest {
    static final LocalDate HOJE = LocalDate.of(2026, 11, 15);
    static final Update V030 = new Update("0.3.0", "", "", URI.create("https://x/a.exe"), "0".repeat(64));

    @Test
    void dialogoSoComAJanelaAbertaEEmFoco() {
        assertEquals(UpdateUi.Form.DIALOG, UpdateUi.form(true, true, true));
        assertEquals(UpdateUi.Form.NOTIFICATION, UpdateUi.form(true, false, true)); // em segundo plano
        assertEquals(UpdateUi.Form.NOTIFICATION, UpdateUi.form(false, false, true)); // fechada na bandeja
        assertEquals(UpdateUi.Form.ON_FOCUS, UpdateUi.form(true, false, false)); // sem bandeja: espera o foco
    }

    @Test
    void nuncaDuranteUmDitado() {
        UpdateUi.Policy p = new UpdateUi.Policy();
        assertNull(p.state(DictationState.LISTENING, HOJE));
        assertNull(p.offer(V030, HOJE));
        assertNull(p.state(DictationState.SILENCE, HOJE));
        assertNull(p.state(DictationState.TRANSCRIBING, HOJE));
        assertSame(V030, p.state(DictationState.PASTED, HOJE)); // terminou: avisa agora
        assertNull(p.state(DictationState.IDLE, HOJE)); // uma vez só
    }

    @Test
    void agoraNaoVoltaSoNoDiaSeguinte() {
        UpdateUi.Policy p = new UpdateUi.Policy();
        assertSame(V030, p.offer(V030, HOJE));
        assertNull(p.offer(V030, HOJE)); // no máximo 1 aviso por versão por dia
        assertSame(V030, p.offer(V030, HOJE.plusDays(1)));
        Update v031 = new Update("0.3.1", "", "", V030.url(), V030.sha256());
        assertSame(v031, p.offer(v031, HOJE.plusDays(1))); // versão nova avisa no mesmo dia
    }

    @Test
    void duasFalhasDeShaSeguidasSoSobraOSite() {
        UpdateUi.Policy p = new UpdateUi.Policy();
        assertFalse(p.corrupted());
        assertTrue(p.corrupted());
        p.downloaded();
        assertFalse(p.corrupted()); // conta só as seguidas
    }

    @Test
    void erroDeRedeMostraOTextoDoDesign() {
        assertTrue(UpdateUi.network(new ConnectException()));
        assertTrue(UpdateUi.network(new IOException(new HttpConnectTimeoutException("x"))));
        assertFalse(UpdateUi.network(new IOException("URL recusada, fora das releases do Arara: x")));
    }

    @Test
    void novidadesUmaPorLinha() {
        assertArrayEquals(new String[] {"Texto ao vivo", "Mais rápido"}, UpdateDialog.notes("Texto ao vivo\n\n Mais rápido \r\n"));
        assertEquals(0, UpdateDialog.notes("").length);
        assertEquals(0, UpdateDialog.notes(null).length);
    }
}
