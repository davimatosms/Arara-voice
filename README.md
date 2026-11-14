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

## Licença

[MIT](LICENSE). O app não manda áudio, texto nem telemetria para lugar nenhum: a única conexão é o download do modelo de voz da Hugging Face. Detalhes na [política de privacidade e de assinatura de código](site/politica.html).

Componentes de terceiros (versões, licenças e links em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)):

- JNativeHook 2.2.2: LGPL-3.0 (o projeto oferece GPL-3.0 ou LGPL-3.0).
- whisper-jni 1.7.1: Apache-2.0, com whisper.cpp e ggml sob MIT.
- FlatLaf e FlatLaf Extras 3.8: Apache-2.0. JSVG 2.2.0: MIT.
- Modelos Whisper (OpenAI, convertidos por ggerganov/whisper.cpp): MIT, baixados na primeira execução.
- OpenJDK 21 (runtime do instalador): GPL-2.0 com Classpath Exception.
- Fontes Nunito Sans, Anton e JetBrains Mono: SIL OFL-1.1.
- Ícones Lucide: ISC. Logos do site (Lobe Icons): MIT; as marcas pertencem aos donos.
