# Arara

Ditado por voz local e open source, feito para o português brasileiro. Aperte o atalho, fale, aperte de novo: o texto aparece onde está o cursor.

Só Windows na primeira versão. Decisões do projeto em [DECISOES.md](DECISOES.md); design em `design/arara-v1.figcraft` (FigCraft).

## Módulos

- `core`: backend (microfone, Whisper, atalho global, colar, histórico, dicionário, configurações).
- `app`: frontend (Swing + FlatLaf, bandeja, janela principal, pop-up da arara).

## Rodar

```
./gradlew :app:run
```

Requer JDK 21.

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
- No GitHub Actions, `.github/workflows/instalador.yml` gera o instalador (em tag `v*` ou à mão) e publica o Setup e o app-image como artefatos. A assinatura pela SignPath fica desligada até existir o secret `SIGNPATH_API_TOKEN` (e a variável `SIGNPATH_ORGANIZATION_ID`); as configurações de artefato estão em `instalador/signpath/`.
