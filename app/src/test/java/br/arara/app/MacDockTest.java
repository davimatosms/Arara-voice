package br.arara.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MacDockTest {
    @Test
    void no_login_abre_escondido_so_quando_nao_ha_nada_para_mostrar() {
        assertTrue(MacDock.startsHidden(true, true, true, false, true));
        assertFalse(MacDock.startsHidden(false, true, true, false, true), "aberto pelo usuário: mostra a janela");
        assertFalse(MacDock.startsHidden(true, false, true, false, true), "só no Mac");
        assertFalse(MacDock.startsHidden(true, true, false, false, true), "sem barra de menus não haveria como abrir");
        assertFalse(MacDock.startsHidden(true, true, true, true, true), "primeira execução precisa das boas-vindas");
        assertFalse(MacDock.startsHidden(true, true, true, false, false), "sem Acessibilidade precisa do aviso");
    }

    @Test
    void esconde_so_no_mac_com_a_opcao_ligada_e_com_barra_de_menus() {
        assertTrue(MacDock.shouldHide(true, true, true));
        assertFalse(MacDock.shouldHide(false, true, true), "fora do Mac não há Dock para esconder");
        assertFalse(MacDock.shouldHide(true, false, true), "opção desligada");
        assertFalse(MacDock.shouldHide(true, true, false), "sem ícone na barra de menus não haveria como reabrir a janela");
    }
}
