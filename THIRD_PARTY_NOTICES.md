# Componentes de terceiros

O Arara é distribuído sob a licença MIT (veja [LICENSE](LICENSE)). Ele usa os componentes abaixo, cada um sob a sua própria licença. Auditoria feita em 2026-11-14 a partir do `./gradlew :app:dependencies --configuration runtimeClasspath` (inclui as transitivas) e dos arquivos do repositório.

## Bibliotecas Java empacotadas no app

| Componente | Versão | Licença | Link |
|---|---|---|---|
| JNativeHook (atalho global; inclui a libuiohook nativa) | 2.2.2 | GPL-3.0 **ou** LGPL-3.0, à escolha. O Arara usa sob a **LGPL-3.0** | https://github.com/kwhat/jnativehook |
| whisper-jni (inclui as DLLs do whisper.cpp e do ggml) | 1.7.1 | Apache-2.0 (whisper.cpp e ggml: MIT) | https://github.com/GiviMAD/whisper-jni, https://github.com/ggml-org/whisper.cpp |
| FlatLaf | 3.8 | Apache-2.0 | https://github.com/JFormDesigner/FlatLaf |
| FlatLaf Extras | 3.8 | Apache-2.0 | https://github.com/JFormDesigner/FlatLaf |
| JSVG (transitiva do FlatLaf Extras) | 2.2.0 | MIT | https://github.com/weisj/jsvg |

Só para testes, fora do app distribuído: JUnit 5 (BOM 5.11.4), EPL-2.0, https://junit.org/junit5/.

**Sobre a LGPL-3.0 do JNativeHook:** ela é compatível com o Arara sob MIT porque o JNativeHook vai como um `.jar` separado e sem modificação, que o usuário pode trocar por outra versão. Quem redistribuir o Arara precisa manter esse `.jar` separado, junto com o texto da LGPL-3.0 e da GPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.txt e https://www.gnu.org/licenses/gpl-3.0.txt), e informar onde está o código-fonte dele. A licença dupla do JNativeHook é GPL/LGPL, ou seja, duas licenças OSI. Não é uma licença comercial.

## Runtime e ferramentas de build que vão no instalador

| Componente | Versão | Licença | Link |
|---|---|---|---|
| OpenJDK (runtime enxuto gerado pelo `jlink`/`jpackage`) | 21 | GPL-2.0 com Classpath Exception | https://openjdk.org/legal/gplv2+ce.html |
| Inno Setup (gera o `.exe` do instalador e embute o seu executável de instalação) | 6.x | Inno Setup License, uma licença própria no estilo BSD. **Não está na lista da OSI** | https://jrsoftware.org/files/is/license.txt |

## Modelos de voz (baixados na primeira execução, não vêm no instalador)

| Modelo | Arquivo | Licença | Link |
|---|---|---|---|
| Whisper base (OpenAI), convertido para ggml | `ggml-base.bin` | MIT | https://huggingface.co/ggerganov/whisper.cpp |
| Whisper small (OpenAI), convertido para ggml | `ggml-small.bin` | MIT | https://huggingface.co/ggerganov/whisper.cpp |
| Whisper large-v3-turbo (OpenAI), quantizado q5_0 | `ggml-large-v3-turbo-q5_0.bin` | MIT | https://huggingface.co/ggerganov/whisper.cpp |

Os pesos originais são da OpenAI, sob MIT (https://github.com/openai/whisper/blob/main/LICENSE). O repositório `ggerganov/whisper.cpp` na Hugging Face também declara MIT. O app baixa os arquivos de um commit fixo (`5359861c739e955e79d9a303bcbc70fb988958b1`) e confere o SHA-256.

## Fontes

| Fonte | Onde | Licença | Link |
|---|---|---|---|
| Nunito Sans | app (arquivos `.ttf` em `app/src/main/resources/br/arara/app/fonts`, com o `OFL.txt`) e site | SIL OFL-1.1 | https://github.com/Fonthausen/NunitoSans |
| Anton | site (pelo Google Fonts); talvez no app no futuro | SIL OFL-1.1 | https://github.com/googlefonts/AntonFont |
| JetBrains Mono | site (pelo Google Fonts) | SIL OFL-1.1 | https://github.com/JetBrains/JetBrainsMono |

Se a Anton entrar no app, o `.ttf` precisa ir junto com o seu `OFL.txt`, do mesmo jeito que a Nunito Sans.

## Ícones e logos

| Componente | Onde | Licença | Link |
|---|---|---|---|
| Lucide (ícones da interface) | app (`app/src/main/resources/br/arara/app/icons`, com o `LICENSE.txt`) | ISC (as partes vindas do Feather: MIT) | https://lucide.dev, https://github.com/lucide-icons/lucide |
| Lobe Icons (`@lobehub/icons-static-svg`): logos de Claude Code, Codex, Cursor, Gemini, GitHub Copilot, Grok e Windsurf | só o site (`site/assets/logos`) | MIT (versão atual no npm: 1.102.0) | https://github.com/lobehub/lobe-icons |

**Marcas registradas:** a licença MIT do Lobe Icons cobre os arquivos SVG, não as marcas. Claude, Codex, Cursor, Gemini, GitHub Copilot, Grok e Windsurf são marcas dos seus donos (Anthropic, OpenAI, Anysphere, Google, GitHub/Microsoft, xAI e Windsurf/Cognition). O site usa os logos só para dizer com quais ferramentas o Arara funciona (uso nominativo), sem sugerir parceria ou patrocínio, e o rodapé avisa que as marcas pertencem aos donos. Os logos não vão no app nem no instalador.

## Arte própria do projeto

| Componente | Origem | Licença |
|---|---|---|
| Arara-canindé em pixel art (sprites do app, do site e do instalador; `arte/`, `app/.../sprites`, `site/assets/img`) | Desenhada no projeto como uma grade de pixels em código (`arte/gerar_arara.py`, `arte/gerar_sprites.py`). A grade foi amostrada de uma imagem de referência gerada no Google Gemini e limpa à mão | MIT, como o resto do projeto |
| Paisagem, nuvens e morros do site | Gerados por código (`site/tools/gerar_assets.py`) | MIT |
| Capturas de tela do app | Feitas do próprio app | MIT |

**Risco da arara:** baixo. Os termos do Google para o Gemini não reivindicam a propriedade das imagens geradas. A arte final é uma grade de 31×42 pixels redesenhada à mão, e o script que a gera fica no repositório. Dois pontos ficam em aberto: (1) a parte puramente gerada por IA pode não ter proteção de direito autoral em alguns países, o que não impede o uso sob MIT; (2) não há como provar que a referência não se parece com uma obra de terceiros. A imagem de referência do Gemini não está no repositório. Se um dia aparecer uma reclamação, a saída é redesenhar a grade do zero, sem referência.
