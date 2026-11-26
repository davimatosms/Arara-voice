# Arara no Linux (beta)

## Instalar

1. Baixe `Arara-x86_64.AppImage` na [página de releases](https://github.com/davimatosms/Arara-voice/releases).
2. Dê permissão: `chmod +x Arara-x86_64.AppImage`.
3. Rode: `./Arara-x86_64.AppImage`.

Dependências:
- `libfuse2` (Ubuntu/Debian: `sudo apt install libfuse2`). Sem ela: `APPIMAGE_EXTRACT_AND_RUN=1 ./Arara-x86_64.AppImage`.
- `libxkbcommon-x11-0` (`sudo apt install libxkbcommon-x11-0`).

## X11

O atalho global Ctrl+Shift+Space e o colar automático funcionam direto.

## Wayland

O Wayland não deixa apps comuns capturar atalhos globais. Crie um atalho do sistema que rode
`<caminho do AppImage> --alternar` (ele só avisa o Arara aberto para iniciar/parar o ditado). O modo "Segurar para falar" não funciona no Wayland: o atalho do sistema não envia o evento de soltar, então `--alternar` sempre liga e desliga.

**GNOME**

Não precisa fazer nada: ao abrir, o Arara cria sozinho o atalho do sistema (aparece em Configurações > Teclado >
Atalhos personalizados como "Arara: ditar") e o mantém igual ao atalho escolhido em Configurações do Arara. O Esc
também funciona em qualquer janela: ele só fica reservado ao Arara enquanto há um ditado em andamento.

Atalhos que o GNOME não registra (botão do mouse, Ctrl ou Alt sozinho) continuam precisando do passo manual:
1. Configurações > Teclado > Atalhos personalizados > "+".
2. Nome: Arara. Comando: `/caminho/Arara-x86_64.AppImage --alternar`.
3. Defina o atalho (por exemplo Ctrl+Shift+Espaço).

**KDE**
1. Configurações do sistema > Atalhos > Adicionar comando.
2. Comando: `/caminho/Arara-x86_64.AppImage --alternar`.
3. Aba Gatilhos: defina o atalho.

### Segurar para falar no Wayland

O atalho do sistema avisa o aperto, mas nunca o soltar. Para o "Segurar para falar" (e para usar Ctrl sozinho
como atalho), o Arara precisa ler o teclado direto do kernel. Rode uma vez num terminal e reabra o Arara:

```
echo 'KERNEL=="event*", SUBSYSTEM=="input", ENV{ID_INPUT_KEYBOARD}=="1", TAG+="uaccess"' | sudo tee /etc/udev/rules.d/70-arara-teclado.rules >/dev/null && sudo udevadm control --reload && sudo udevadm trigger --subsystem-match=input
```

O mesmo comando está em Configurações > Linux > "Copiar comando". Estar no grupo `input` também serve.

Saiba o que isso libera: qualquer programa da sua sessão passa a poder ler as teclas, como já acontece no X11. O Arara
só lê (não prende o teclado) e só olha o atalho e o Esc. Para desfazer, apague o arquivo
`/etc/udev/rules.d/70-arara-teclado.rules` e reinicie a sessão.

Botão do mouse como atalho continua sem funcionar no Wayland. Funciona em qualquer desktop Wayland (GNOME, KDE, wlroots). No GNOME o atalho do sistema continua registrado, só para
a tecla não chegar também ao app em foco.

### Colar automático no Wayland

O Arara tenta, na ordem: `wtype` (KDE e wlroots), `ydotool` (com o `ydotoold` rodando) e o portal
de entrada remota (GNOME; precisa de `python3-gi` e de uma permissão aceita na primeira vez).
Se nenhum funcionar, o texto fica no clipboard e o pop-up avisa: "Texto copiado: cole com Ctrl+V".

### Ctrl+V ou Ctrl+Shift+V

Terminais só colam com Ctrl+Shift+V, então esse é o padrão no Linux (X11 e Wayland). Na maioria dos apps ele cola
igual (em alguns, como texto sem formatação). Se algum app estranhar (o LibreOffice abre o "Colar especial"), troque
em Configurações > Linux > "Colar com".

## Diagnóstico do colar

Deixe um editor de texto focado e rode:

```
./Arara-x86_64.AppImage --diagnostico-colar
```

Ele lista as ferramentas encontradas e cola uma frase de teste após 5 segundos.

## Como reportar problema

Abra uma issue em https://github.com/davimatosms/Arara-voice/issues com:
- distro e versão;
- versão do Arara;
- X11 ou Wayland;
- desktop (GNOME, KDE...);
- saída do `--diagnostico-colar`.
