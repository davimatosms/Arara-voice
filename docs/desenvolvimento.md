# Desenvolvimento, instalador e releases

Notas técnicas que ficavam no README. Decisões do projeto em [DECISOES.md](../DECISOES.md); design em
`design/arara-v1.figcraft` (FigCraft).

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

Num Mac, gera `build/macos/Arara-0.1.0-macos-<arm64|x64>.dmg` (sem Developer ID, assinatura ad-hoc). O CI faz o mesmo no workflow `macos`. Como abrir o app sem assinatura, permissões e atalho: [macos.md](macos.md).

## Linux

```
./gradlew :app:appimage
```

Num Linux com JDK 21, gera `build/linux/Arara-<versão>-linux-<x64|arm64>.AppImage`. Precisa do
[appimagetool](https://github.com/AppImage/appimagetool/releases/tag/1.9.1) no `PATH` ou em `APPIMAGETOOL`.
Exemplo x86_64 (hash fixo da 1.9.1):

```
curl -fsSL -o appimagetool \
  https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
echo ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  appimagetool | sha256sum -c -
chmod +x appimagetool
export APPIMAGETOOL="$PWD/appimagetool"
```

O CI faz o mesmo no workflow `linux` (x64 e arm64). Dados do app: `~/.local/share/Arara`. Atalho global e colar
dependem de X11 ou XWayland. Ao publicar a release, anexe também as cópias `Arara-Linux-x64.AppImage` e
`Arara-Linux-arm64.AppImage` (os botões do README usam esses nomes).

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
- **Atualização no app:** o Arara consulta `site/versao.json` (publicado em `https://arara-voice.vercel.app/versao.json` a cada merge na `main`) e pergunta ao usuário se há versão nova. Regra a cada release, depois de anexar os arquivos com versão no nome (`Arara-Setup-<versão>.exe`, `Arara-<versão>-Mac-AppleSilicon.dmg`, `Arara-<versão>-Mac-Intel.dmg`, `Arara-<versão>-linux-x64.AppImage`, `Arara-<versão>-linux-arm64.AppImage`): rode `python instalador/gerar_versao.py <versão> "<notas curtas>" <os 5 arquivos>` e abra um PR com o `site/versao.json` novo. Só faça o merge depois de a release estar publicada: o app baixa das URLs da release e confere o SHA-256.
- No GitHub Actions, `.github/workflows/instalador.yml` gera o instalador (em tag `v*` ou à mão) e publica o Setup e o app-image como artefatos. A assinatura pela SignPath fica desligada até existir o secret `SIGNPATH_API_TOKEN` (e a variável `SIGNPATH_ORGANIZATION_ID`); as configurações de artefato estão em `instalador/signpath/`.

## Mídia do README

O GIF, o mosaico dos temas, a captura da janela e a imagem social (`docs/midia/`) saem do próprio app, desenhados
fora da tela pelo `ScreenshotTest` (sem abrir janela e sem usar o atalho):

```
./gradlew :app:test --tests "br.arara.app.ScreenshotTest.desenhaVitrine" --tests "br.arara.app.ScreenshotTest.desenhaTemas"
python docs/midia/gerar_midia.py
```

O script precisa do Pillow (`pip install pillow`). A imagem social (`docs/midia/social-preview.png`, 1280x640) só
dá para subir pelo site do GitHub: Settings › General › Social preview.
