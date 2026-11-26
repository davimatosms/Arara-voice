# Arara no macOS

O `.dmg` sai do workflow `macos` do GitHub Actions, em duas versões:

- `Arara-<versão>-macos-arm64.dmg`: Macs com Apple Silicon (M1, M2, M3, M4...).
- `Arara-<versão>-macos-x64.dmg`: Macs com processador Intel.

Para saber qual é o seu: menu Apple › Sobre Este Mac. "Chip Apple M..." é arm64; "Processador Intel" é x64.

## Instalar e abrir (app sem assinatura da Apple)

O projeto ainda não tem o Developer ID da Apple (US$ 99 por ano). Por isso o app é assinado só "ad-hoc" e o
macOS bloqueia a primeira abertura com o aviso de que não pode verificar o desenvolvedor. É esperado.

1. Abra o `.dmg` e arraste o **Arara** para a pasta **Aplicativos**.
2. Abra o Arara pelos Aplicativos. Vai aparecer o aviso "O Arara não foi aberto". Clique em **OK** (não em
   "Mover para o Lixo").
3. Abra **Ajustes do Sistema › Privacidade e Segurança**, desça até a seção **Segurança** e clique em
   **Abrir Mesmo Assim** ao lado de "O Arara foi bloqueado". Confirme com a sua senha.
4. Abra o Arara de novo e clique em **Abrir**. Daqui para frente ele abre normalmente.

No macOS Sequoia (15) e mais novos, o atalho antigo de clicar com o botão direito › Abrir não pula mais o aviso:
use o passo 3.

Alternativa pelo Terminal (tira a marca de "baixado da internet"):

```sh
xattr -dr com.apple.quarantine /Applications/Arara.app
```

## Permissões

| Permissão | Para quê | Quando o macOS pede |
|---|---|---|
| Microfone | ouvir enquanto você dita | no passo "Microfone" da primeira execução |
| Acessibilidade | o atalho global (JNativeHook) e colar o texto com Cmd+V | no passo "Atalho" da primeira execução |
| Monitoramento de Entrada | o atalho global, se o macOS pedir também | junto com a Acessibilidade |

Se negar sem querer: Ajustes do Sistema › Privacidade e Segurança › Microfone (ou Acessibilidade) e ligue o
Arara. O botão "Abrir Ajustes" do app abre o painel certo. Depois de uma atualização do app, pode ser preciso
desligar e ligar o Arara de novo na lista (a assinatura ad-hoc muda a cada versão).

Se depois de atualizar o Arara aparece ligado em Acessibilidade mas o atalho não funciona, a entrada é da
assinatura antiga. Use "Zerar e reabrir" no aviso do app, ou no Terminal (sem sudo):

```sh
tccutil reset Accessibility br.arara.Arara
```

Reabra o Arara e ligue de novo em Acessibilidade.

## Atalho

O padrão é **Ctrl+Shift+Espaço**, igual ao Windows. No macOS ele não é atalho do sistema e não digita
caractere. Ficaram de fora: Cmd+Espaço (Spotlight), Ctrl+Espaço e Ctrl+Option+Espaço (fonte de entrada),
Cmd+Option+Espaço (busca do Finder), Ctrl+Cmd+Espaço (emojis), Cmd+Shift+Espaço (Inteligência Visual no macOS 27)
e Option+Espaço (digita espaço não separável). Dá para trocar em Configurações.

## Atualização pelo app

Quando há versão nova no `versao.json` do site, o Arara pergunta. Se você aceitar, ele:

1. baixa o `.dmg` da release do GitHub para a pasta temporária e confere o SHA-256 (diferente: apaga e para);
2. monta o `.dmg` com `hdiutil attach -nobrowse -readonly`, copia o `Arara.app` com `ditto` para
   `.Arara-nova.app`, ao lado do app atual (ex.: em `/Applications`), e desmonta;
3. fecha o Arara; um script pequeno espera ele sair, troca o `.app` (guardando o antigo até o novo estar no lugar)
   e reabre com `open`.

Quarentena: o arquivo baixado pelo Arara (HttpClient do Java) não ganha o atributo `com.apple.quarantine`, que só é
posto por apps que pedem isso (navegadores, Mail etc.). O app novo abre sem o aviso do Gatekeeper. Para conferir:
`xattr -l /Applications/Arara.app` não mostra `com.apple.quarantine`.

Se o Arara estiver numa pasta sem permissão de escrita (ex.: `/Applications` num usuário sem administrador), ele
não tenta: avisa e pede para baixar a versão nova no site. Depois da troca, pode ser preciso ligar de novo o Arara
em Acessibilidade (a assinatura ad-hoc muda a cada versão).

## Só na barra de menus (sem Dock)

Ao fechar a janela, o Arara sai do Dock e do Cmd+Tab e continua rodando só com o ícone da barra de menus, ouvindo o
atalho. Para abrir a janela de novo, clique no ícone da barra de menus › **Abrir**; o Dock volta enquanto ela
estiver aberta. Para sair de vez: ícone da barra de menus › **Sair**.

Com **Abrir ao iniciar sessão** ligado, o Arara já começa assim: sem janela, só na barra de menus. Ele só mostra a
janela no login na primeira execução e quando falta a permissão de Acessibilidade (o aviso precisa dela). Abrir o
app pelos Aplicativos continua mostrando a janela.

Quem prefere o Dock sempre visível desliga **Configurações › Geral › Esconder do Dock ao fechar a janela**.
Minimizar a janela não esconde o app do Dock (ela vai para lá, como em qualquer app).

## Onde ficam os dados

`~/Library/Application Support/Arara/` (histórico, dicionário, configurações e modelos de voz).
"Abrir ao iniciar sessão" grava `~/Library/LaunchAgents/br.arara.Arara.plist`.

## Gerar no seu Mac

```sh
./gradlew :app:dmg      # build/macos/Arara-<versão>-macos-<arq>.dmg
./gradlew :core:smoke --args="build/smoke"   # fumaça: Whisper com o modelo leve e o JNativeHook
```

Se você rebuilda e reinstala com frequência, a permissão de Acessibilidade quebra a cada build (a assinatura
ad-hoc muda). A seção seguinte resolve.

## Assinatura estável em desenvolvimento (certificado local)

O macOS amarra as permissões (Acessibilidade, Microfone) à assinatura do app, e a assinatura ad-hoc muda a
cada build — por isso todo rebuild quebra o atalho até o Arara ser ligado de novo nos Ajustes. Para
desenvolver sem isso, crie uma vez um certificado local de assinatura:

```sh
instalador/macos/criar_cert_local.sh
```

Ele gera um certificado autoassinado "Arara Dev Local" (válido por 10 anos) no chaveiro de login. A partir
daí a tarefa `macApp` assina com ele sozinha — confira com `codesign -dv app/build/jpackage-mac/Arara.app`,
que mostra `Authority=Arara Dev Local`. Como a assinatura não muda mais, a Acessibilidade concedida uma vez
sobrevive a rebuilds e reinstalações. Para outro nome de identidade: `./gradlew :app:dmg
-PassinaturaMac="Meu Cert"`.

O certificado é só seu e só vale na sua máquina: para qualquer outra pessoa o Gatekeeper continua bloqueando
o app (o fluxo de "Abrir Mesmo Assim" do instalador não muda). Ele não substitui o Developer ID da seção
seguinte — é só para desenvolvimento.

## Quando houver Developer ID

Crie os secrets `APPLE_CERTIFICATE_P12` (o .p12 em base64), `APPLE_CERTIFICATE_PASSWORD`,
`APPLE_SIGNING_IDENTITY`, `APPLE_ID`, `APPLE_TEAM_ID` e `APPLE_APP_PASSWORD`. O passo "Assinar com Developer ID
e notarizar" do `.github/workflows/macos.yml` liga sozinho, com hardened runtime e
`instalador/macos/entitlements.plist`.
