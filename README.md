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
