package br.arara.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MacDockTest {
    @Test
    void esconde_so_no_mac_com_a_opcao_ligada_e_com_barra_de_menus() {
        assertTrue(MacDock.shouldHide(true, true, true));
        assertFalse(MacDock.shouldHide(false, true, true), "fora do Mac não há Dock para esconder");
        assertFalse(MacDock.shouldHide(true, false, true), "opção desligada");
        assertFalse(MacDock.shouldHide(true, true, false), "sem ícone na barra de menus não haveria como reabrir a janela");
    }
}
