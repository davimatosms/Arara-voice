# Listas "awesome": onde o Arara cabe e o texto pronto

Pesquisa de 16/11/2026. Nada aqui foi enviado: são os textos prontos para você abrir o PR (ou o formulário) com a sua
conta. Uma sugestão por PR, como todas pedem. Abra o PR só depois de este README (com o GIF) estar na `main`: os
mantenedores olham o repositório antes de aceitar.

Situação do repo hoje: criado em 13/11/2026, 1 estrela. Duas listas pedem idade mínima ou estrelas; elas estão no fim,
com a data a partir da qual vale a pena tentar.

## Prontas para enviar agora

### 1. awesome-mac (jaywcjlove/awesome-mac)

- Link: https://github.com/jaywcjlove/awesome-mac (~118 mil estrelas, commit hoje)
- Aceita? Sim. A seção **Voice-to-Text** está cheia de apps de ditado local parecidos (Amical, VoiceInk, TypeWhisper,
  OpenQuack). Regras: ordem alfabética na seção, uma frase, e o mesmo item em `README.md`, `README-zh.md`,
  `README-ja.md` e `README-ko.md` (o `README-pt.md` também existe; vale incluir).
- Posição: entre `Amical` e `buzz` em todos os arquivos.
- Título do PR: `Add Arara`

`README.md`, seção `## Voice-to-Text`:

```markdown
* [Arara](https://arara-voice.vercel.app) - Free, fully local dictation for Brazilian Portuguese that pastes at the cursor in any app, with a live text preview, powered by whisper.cpp. [![Open-Source Software][OSS Icon]](https://github.com/davimatosms/Arara-voice) ![Freeware][Freeware Icon]
```

`README-pt.md`, seção `## Voz para Texto`:

```markdown
* [Arara](https://arara-voice.vercel.app) - Ditado grátis e 100% local para português do Brasil que cola o texto no cursor em qualquer app, com prévia ao vivo, usando o whisper.cpp. [![Open-Source Software][OSS Icon]](https://github.com/davimatosms/Arara-voice) ![Freeware][Freeware Icon]
```

`README-ja.md`, seção `## 音声テキスト変換`:

```markdown
* [Arara](https://arara-voice.vercel.app) - ブラジルポルトガル語向けの無料・完全ローカルなディクテーションアプリで、話した内容をライブプレビューしながら任意のアプリのカーソル位置に貼り付け。whisper.cpp を使用。 [![Open-Source Software][OSS Icon]](https://github.com/davimatosms/Arara-voice) ![Freeware][Freeware Icon]
```

`README-ko.md`, seção `## 음성 텍스트 변환 (Voice-to-Text)`:

```markdown
* [Arara](https://arara-voice.vercel.app) - 브라질 포르투갈어용 무료 완전 로컬 받아쓰기 앱으로, 실시간 미리보기와 함께 어떤 앱에서나 커서 위치에 텍스트를 붙여 넣음. whisper.cpp 기반. [![Open-Source Software][OSS Icon]](https://github.com/davimatosms/Arara-voice) ![Freeware][Freeware Icon]
```

`README-zh.md`: lá não há seção de voz; o Amical e o buzz estão em `## 音频和视频`, também em ordem alfabética (o Arara
entra logo depois de `Amical`, antes de `AnimacX`):

```markdown
* [Arara](https://arara-voice.vercel.app) - 免费、完全本地运行的巴西葡萄牙语语音输入工具，边说边实时预览，并把文字粘贴到任何应用的光标处，基于 whisper.cpp。 [![Open-Source Software][OSS Icon]](https://github.com/davimatosms/Arara-voice) ![Freeware][Freeware Icon]
```

### 2. open-source-mac-os-apps (serhii-londar/open-source-mac-os-apps)

- Link: https://github.com/serhii-londar/open-source-mac-os-apps (~51 mil estrelas, commit em 31/10/2026)
- Aceita? Sim, tem dezenas de apps de ditado (Plainsay, Vocula, OpenQuack). Mas atenção às regras:
  - não se edita o README: o PR muda o `applications.json` (o README é gerado);
  - "README is not clear or not written in English" torna o projeto inelegível. O nosso README é em português com um
    resumo em inglês no topo. Pode passar; se recusarem por isso, a saída é um `README.en.md` completo.
- Seções: categorias `audio` e `productivity` (as mesmas de apps parecidos). Linguagem: `java`.
- Título do PR: `Add Arara`

Objeto para colar no array `applications` (no fim, ou em ordem, conforme o arquivo estiver):

```json
        {
            "short_description": "Free, fully local push-to-talk dictation for Brazilian Portuguese that pastes at the cursor, with a live text preview, powered by whisper.cpp.",
            "categories": [
                "audio",
                "productivity"
            ],
            "repo_url": "https://github.com/davimatosms/Arara-voice",
            "title": "Arara",
            "icon_url": "https://raw.githubusercontent.com/davimatosms/Arara-voice/main/app/src/main/resources/br/arara/app/araras/caninde/icone-256.png",
            "screenshots": [
                "https://raw.githubusercontent.com/davimatosms/Arara-voice/main/docs/midia/arara-demo.gif",
                "https://raw.githubusercontent.com/davimatosms/Arara-voice/main/docs/midia/janela-inicio.png"
            ],
            "official_site": "https://arara-voice.vercel.app",
            "languages": [
                "java"
            ]
        }
```

### 3. awesome-ai-coding-tools (ai-for-developers/awesome-ai-coding-tools)

- Link: https://github.com/ai-for-developers/awesome-ai-coding-tools (~2,3 mil estrelas, commit em 18/09/2026)
- Aceita? Sim: pede ferramenta com IA, útil para desenvolvedor e com versão grátis. A seção **Developer Productivity
  Tools** já tem apps de ditado para prompts (OpenQuack, "Whisper by Remskill").
- Regra: adicionar no **fim** da seção, neste formato (travessão longo):
- Título do PR: `Add Arara`

```markdown
- **[Arara](https://github.com/davimatosms/Arara-voice)** – Free, open-source voice dictation for Windows and macOS that runs Whisper locally and pastes prompts in Brazilian Portuguese at the cursor of Claude Code, Codex, Cursor or any app.
```

## Esperar um pouco

### 4. awesome-claude-code (hesreallyhim/awesome-claude-code)

- Link: https://github.com/hesreallyhim/awesome-claude-code (~58 mil estrelas, commit hoje)
- Aceita? Talvez. A seção **Remote Control, Notifications & Voice I/O** tem o `dictate` (ditado local para o Claude
  Code no Linux), então o tipo cabe. Mas o checklist pede "This resource is specific to Claude Code", e o Arara serve
  para qualquer app: é a parte fraca do pedido.
- Regras: só por formulário na web (não aceita PR nem `gh`), feito por uma pessoa; o projeto precisa ter **14 dias
  desde o primeiro commit e commits depois disso**, ou 100 estrelas. Primeiro commit: 13/11/2026, então **a partir de
  27/11/2026**.
- Formulário: https://github.com/hesreallyhim/awesome-claude-code/issues/new?template=recommend-resource.yml

Campos:

- Display Name: `Arara`
- Category: `Remote Control, Notifications & Voice I/O`
- Link: `https://github.com/davimatosms/Arara-voice`
- Author Name: `davimatosms`
- Author Link: `https://github.com/davimatosms`
- Description (uma linha, descritiva, sem emoji e sem falar com o leitor):

```text
Local, offline push-to-talk dictation for Windows and macOS built on whisper.cpp and tuned for Brazilian Portuguese: a global hotkey records, a pop-up shows the live transcript, and the whole prompt is pasted at once into the Claude Code input (or any other app). Free and MIT-licensed, with no account, cloud or telemetry.
```

### 5. awesome-whisper (sindresorhus/awesome-whisper)

- Link: https://github.com/sindresorhus/awesome-whisper (~2,4 mil estrelas, commit em 17/09/2026)
- Aceita? O tipo, sim (seção **Apps** tem VoiceInk, OpenSuperWhisper, TypeWhisper). Mas a regra é clara: projeto
  open source no GitHub precisa de **pelo menos 100 estrelas**, e o link vai para o repo, não para o site. Tentar
  quando passar de 100.
- Regras: no fim da categoria, `[Title](link) - Description.`, em inglês, sem slogan; título do PR `Add Arara`.

```markdown
- [Arara](https://github.com/davimatosms/Arara-voice) - Dictation app for Windows and macOS focused on Brazilian Portuguese, with a live preview and paste at the cursor. (FOSS)
```

## Descartadas (e por quê)

- **awesome-windows (0PandaDEV/awesome-windows)**: o aviso dos mantenedores diz, com essas palavras, que "não é uma
  lista para a sua nova ferramenta de IA ou wrapper de Whisper" e que esses PRs são recusados. Não vale o desgaste.
- **awesome-brasil (vinicius73/awesome-brasil)**: lista de bibliotecas brasileiras por linguagem (PHP, Ruby, JS), sem
  seção de apps, e sem commit desde maio de 2024.
- **awesome-privacy (pluja e Lissy93)**: não têm categoria de ditado ou voz; um item avulso seria recusado.
- **awesome-java (akullpp)**: lista de bibliotecas e frameworks, não de aplicativos.
