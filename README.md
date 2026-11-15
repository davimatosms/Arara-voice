# Arara

Ditado por voz local e open source, feito para o português brasileiro. Aperte o atalho, fale, aperte de novo: o texto aparece onde está o cursor.

Windows e macOS (Apple Silicon e Intel). Decisões do projeto em [DECISOES.md](DECISOES.md); design em `design/arara-v1.figcraft` (FigCraft).

## Módulos

- `core`: backend (microfone, Whisper, atalho global, colar, histórico, dicionário, configurações).
- `app`: frontend (Swing + FlatLaf, bandeja, janela principal, pop-up da arara).

## Rodar

```
./gradlew :app:run
```

Requer JDK 21.

## macOS

```
./gradlew :app:dmg
```

Num Mac, gera `build/macos/Arara-0.1.0-macos-<arm64|x64>.dmg` (sem Developer ID, assinatura ad-hoc). O CI faz o mesmo no workflow `macos`. Como abrir o app sem assinatura, permissões e atalho: [docs/macos.md](docs/macos.md).

## Instalador (Windows)

```
./gradlew :app:instalador
```

Gera `build/instalador/Arara-Setup-0.1.0.exe` (~39 MB). Requer JDK 21 e [Inno Setup 6.7+](https://jrsoftware.org/isinfo.php); o Gradle procura o `ISCC.exe` em `%LOCALAPPDATA%\Programs\Inno Setup 6`, no `PATH` ou em `-Piscc=C:\caminho\ISCC.exe`.

- `./gradlew :app:jpackageImage`: só o app-image em `app/build/jpackage/Arara` (`Arara.exe` + runtime enxuto do jlink, ~57 MB, sem WiX).
- `instalador/arara.iss`: script do Inno (por usuário, sem UAC, em `%LOCALAPPDATA%\Programs\Arara`, atalho no Menu Iniciar).
- `instalador/assets/`: imagens exportadas do FigCraft (página "10 · Instalador") e o ícone. O 16 px é retocado à mão em `instalador/icone.py`, que também gera o `arara.ico`.
- O modelo de voz não vai no instalador: o app baixa na primeira vez.
- O desinstalador não apaga `%APPDATA%\Arara` (configurações, histórico e modelos).
- A versão vem só de `gradle.properties` e vai para os jars, o `Arara.exe`, o Setup e o desinstalador.
- Ao publicar uma release, anexe o instalador também com o nome fixo `Arara-Setup.exe`: os botões do site baixam direto de `releases/latest/download/Arara-Setup.exe`.
- **Atualização no app:** o Arara consulta `site/versao.json` (publicado em `https://arara-voice.vercel.app/versao.json` a cada merge na `main`) e pergunta ao usuário se há versão nova. Regra a cada release, depois de anexar os arquivos com versão no nome (`Arara-Setup-<versão>.exe`, `Arara-<versão>-Mac-AppleSilicon.dmg`, `Arara-<versão>-Mac-Intel.dmg`): rode `python instalador/gerar_versao.py <versão> "<notas curtas>" <os 3 arquivos>` e abra um PR com o `site/versao.json` novo. Só faça o merge depois de a release estar publicada: o app baixa das URLs da release e confere o SHA-256.
- No GitHub Actions, `.github/workflows/instalador.yml` gera o instalador (em tag `v*` ou à mão) e publica o Setup e o app-image como artefatos. A assinatura pela SignPath fica desligada até existir o secret `SIGNPATH_API_TOKEN` (e a variável `SIGNPATH_ORGANIZATION_ID`); as configurações de artefato estão em `instalador/signpath/`.

## Licença

[MIT](LICENSE). O app não manda áudio, texto nem telemetria para lugar nenhum. Ele só se conecta para baixar o modelo de voz da Hugging Face e para consultar o `versao.json` do site (verificação de atualizações, que dá para desligar; o instalador só é baixado se você aceitar). Detalhes na [política de privacidade e de assinatura de código](site/politica.html).

Componentes de terceiros (versões, licenças e links em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)):

- JNativeHook 2.2.2: LGPL-3.0 (o projeto oferece GPL-3.0 ou LGPL-3.0).
- whisper-jni 1.7.1: Apache-2.0, com whisper.cpp e ggml sob MIT.
- FlatLaf e FlatLaf Extras 3.8: Apache-2.0. JSVG 2.2.0: MIT.
- Modelos Whisper (OpenAI, convertidos por ggerganov/whisper.cpp): MIT, baixados na primeira execução.
- OpenJDK 21 (runtime do instalador): GPL-2.0 com Classpath Exception.
- Fontes Nunito Sans, Anton e JetBrains Mono: SIL OFL-1.1.
- Ícones Lucide: ISC. Logos do site (Lobe Icons): MIT; as marcas pertencem aos donos.
