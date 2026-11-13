# Decisões do Projeto

App de ditado por voz, local e open source, focado em português brasileiro.
## Identidade: projeto brasileiro

- **O projeto é brasileiro**, sempre. Feito por brasileiros, para brasileiros.
- **Público:** pessoas que sofrem com ferramentas de ditado que não entendem bem o português brasileiro.
- **Propósito:** ser o app de ditado que entende o PT-BR de verdade, com sotaques, gírias e pontuação certos.
- **O mascote é uma arara brasileira** (espécie a escolher; ver Conceito visual).
- Essa identidade guia nome, visual, textos do app e prioridades técnicas: o PT-BR vem sempre em primeiro lugar.

## Nome

**Arara** (definido por enquanto). É curto, simples, todo brasileiro conhece e funciona bem numa futura landing page. O mascote passa a ser uma **arara brasileira**.

Conflito de marca não é problema: o projeto é open source e não será vendido.

Descartados: Parrot, Currupaco, Louro, Tagarela, Ajuru, Maracanã.

Candidatos atuais:

- **Papo**: conversa ("bater papo") e também a parte do pescoço das aves.
- **Pena**: a pena do papagaio e a pena de escrever.
- **Dito**: "o que foi dito"; lembra "dito e feito".
- **Bico**: o bico do papagaio; "abrir o bico" é falar.
- **Pio**: o som da ave; "não deu um pio".
- **Arara**: ave brasileira, curta e sonora.
- **Falaê**: "fala aí", bem informal e brasileiro.

## Motivação

O FluidVoice erra muito no reconhecimento de PT-BR. A ideia é criar uma alternativa leve, que rode na máquina do usuário e sirva também como projeto de portfólio.

## Plataforma

- **Só Windows** na primeira versão.
- Linux e macOS ficam para depois.

## Linguagem e stack

| Área | Escolha | Observação |
|---|---|---|
| Linguagem | Java 21+ | |
| Reconhecimento de voz | whisper.cpp via JNI | Verificar se o `whisper-jni` é mantido e tem build com Vulkan; senão, compilar a dll própria |
| Captura do microfone | `javax.sound.sampled` | 16 kHz, mono, PCM (formato do Whisper) |
| Atalho global (teclado e mouse) | JNativeHook | Java puro não captura teclas fora da janela |
| Colar o texto | Clipboard + `java.awt.Robot` (Ctrl+V) | Mais compatível entre apps |
| UI | `SystemTray` + Swing com FlatLaf | JavaFX é pesado demais para isso |
| Instalador | `jpackage` (.msi com JRE embutido) | |
| Modelo | Baixado na primeira execução | Opções: leve / equilibrado / preciso |

## Aceleração (GPU)

- **Padrão: CPU.** Funciona em qualquer máquina. Estimativa: ~1–3 s por frase com o modelo turbo quantizado numa CPU boa (ainda não medido).
- **Aceleração: Vulkan.** Funciona em placas NVIDIA, AMD e Intel, e o driver já vem com o Windows. Deve ser mais rápido que CPU.
- **CUDA: melhoria futura.** Só funciona em NVIDIA e deixa o instalador centenas de MB maior.

## Modelo e qualidade em PT-BR

- Modelo principal: Whisper `large-v3-turbo` quantizado (~550 MB). Para máquinas fracas: `small` ou `base`.
- Forçar `language="pt"`, porque a detecção automática erra em áudio curto.
- Usar um *initial prompt* em PT-BR com pontuação para orientar o estilo da saída.
- Dicionário de termos do usuário (nomes, jargões) aplicado no pós-processamento.

## Interação (fluxo de uso)

1. O usuário aciona o atalho, e o sistema **começa a escutar**.
2. Enquanto escuta, aparece um **pop-up com uma barrinha de áudio** se mexendo conforme a voz, para mostrar que o microfone está funcionando.
3. O usuário aciona **o mesmo atalho de novo**. O sistema para, transcreve, **salva no histórico** e depois **cola o texto** onde estiver o cursor.
4. **Esc** cancela sem colar.

### Pop-up de gravação

O print do FluidVoice serviu só para mostrar a **função**. O design do nosso app será **totalmente próprio**, com identidade visual diferente. O visual ainda vai ser definido.

Requisitos funcionais (valem para qualquer design):

- Mostra que o microfone está captando, com um indicador animado que reage ao volume da voz.
- Mostra o estado atual: ouvindo / transcrevendo.
- Fica sempre por cima das outras janelas e **não rouba o foco**, para o colar ir para a janela certa.
- É pequeno e não atrapalha o que o usuário está fazendo.

#### Conceito visual: arara 8-bit

O mascote do app é uma **arara em pixel art (8-bit)** sentada num galho, que reage ao que escuta.

- **Galho:** a arara fica empoleirada, com as patinhas no galho. As **ondinhas de som** aparecem ao longo do galho e se mexem conforme o volume da voz.
- **Escutando (há fala):** a arara reage ao volume, não às palavras. Bate as asas, mexe a cabeça, abre o bico e faz pequenas animações variadas para não ficar repetitivo.
- **Silêncio:** inclina a cabeça e aparece um **"?"** acima dele, como se não estivesse entendendo. Dispara após alguns segundos sem fala (valor a definir, ex.: 2–3 s).
- **Transcrevendo:** animação própria, ainda a definir (ex.: arara "pensando").
- Referência de estilo: o polvo 8-bit do projeto Octo, de um colega (octo.marujo.dev). Serve só como referência de estilo; a arara não deve copiar os elementos dele (círculo de fundo, chapéu de marinheiro, ícones de terminal).
  - O que aproveitar: pixel art com contorno escuro, paleta pequena e viva, olhos grandes com brilho (personagem fofo e expressivo), poucas partículas animadas em volta (no Octo são bolhas; no nosso podem ser notas musicais ou ondinhas de som) e um fundo escuro que faz o personagem se destacar.
  - Espécie (a escolher): **arara-canindé** (azul e amarela, cores do Brasil), **arara-vermelha** ou **arara-azul** (do Pantanal). Evitar a ararinha-azul por causa da associação com o filme "Rio".

Ferramentas para criar o personagem:

- **Rascunho inicial:** o Claude gera os sprites por código (uma matriz de pixels transformada em PNG por um script) e mostra uma prévia para aprovar. Não precisa instalar nada para isso.
- **Refinamento manual:** **LibreSprite** (gratuito), **Piskel** (gratuito, roda no navegador) ou **Aseprite** (pago, cerca de US$ 20, padrão do mercado para pixel art e animação).
- Formato final: spritesheet PNG com fundo transparente, em grade pequena (ex.: 32×32 ou 48×48 pixels), ampliada em escala inteira para não borrar.

Implementação:

- Sprites em PNG (spritesheet), um conjunto de quadros por estado: ocioso, escutando, silêncio, transcrevendo.
- Animação desenhada em Swing (janela sem borda e com fundo transparente), trocando de quadro com um timer.
- O estado vem do VAD (fala ou silêncio) e o nível do áudio (RMS) controla as ondinhas e a intensidade das reações.
- A arte precisa ser própria ou ter licença livre, porque o projeto é open source. Ferramenta sugerida para desenhar: Aseprite ou LibreSprite (gratuito).

### Histórico

- **Toda** transcrição é salva no histórico, com data e hora, **antes** de tentar colar.
- Se o colar falhar (ou se a gravação for encerrada pelo limite de silêncio), o texto continua no histórico e não se perde.
- Tela "Histórico" no app: lista as transcrições, com um botão de copiar em cada uma.
- **Sem limite de itens**: tudo o que o usuário falar fica salvo.
- **Formato: JSON simples**, sem SQLite. Mais especificamente JSON Lines (`historico.jsonl`), com um objeto JSON por linha. Cada transcrição nova é só acrescentada no fim do arquivo, sem reescrever tudo. Isso continua rápido com histórico grande, e um travamento no meio do salvamento não corrompe as entradas antigas.
- Exemplo de linha: `{"data":"2026-11-13T14:32:05","texto":"..."}`
- **Local e por usuário**: salvo em `%APPDATA%\<nome-do-app>\`, que é a pasta própria de cada usuário do Windows. Cada pessoa na mesma máquina tem o seu histórico, e nada sai do computador.
- O texto fica em disco sem criptografia, então é preciso ter um botão para apagar o histórico.

### Atalho

- Modo **toggle**: aperta uma vez para começar e de novo para terminar. Não é preciso segurar.
- O padrão é uma combinação de teclas (ex.: Ctrl+Alt+algo; o exemplo foi só ilustrativo).
- **O usuário pode trocar** por qualquer combinação de teclas ou por um **botão do mouse** nas configurações.
- Atenção: no Windows, Ctrl+Alt sozinho equivale ao **AltGr**, que no ABNT2 digita `/ ? ° ª º`. O padrão final não pode ser só Ctrl+Alt.

### Limite de segurança

- Se ficar **5 minutos sem detectar fala**, a gravação é desligada sozinha.
- Enquanto houver fala, a gravação **não** é interrompida, seja qual for a duração.
- Quando o limite dispara, o que já foi dito é transcrito, salvo no histórico e colado.
- Precisa de VAD (detecção de voz) para separar silêncio de fala. Candidato: Silero VAD, ou o VAD que já vem no whisper.cpp.

## Design do app (janela principal)

Ferramenta de design: **FigCraft** (app local). O design começa nele depois que as decisões abaixo forem fechadas.

### Referência: FluidVoice (estrutura, não visual)

Prints do FluidVoice recebidos em 2026-11-13. Servem como base de **estrutura e funções**. O visual (cores, botões, tipografia) será próprio e ainda vai ser definido com as referências de design que o usuário vai mandar.

O que o FluidVoice tem:

- **Layout:** barra lateral fixa à esquerda (logo, menu, cartão de perfil embaixo) e conteúdo à direita. Tema escuro, com destaque azul no item ativo e verde para estados "ok".
- **Selo de status** no canto superior direito (ex.: "Pasted") mostrando o resultado da última ação.
- **Home:** checklist de configuração (modelo de voz, microfone, motor de voz, inserção de texto, histórico), cada item com selo "Done"/"On". Embaixo, cartão "Pronto para ditar" com botão grande de microfone, o atalho atual e uma barra de nível de áudio.
- **Histórico:** busca, botões de atualizar e apagar, contador de ditados. Lista à esquerda (app de origem, tempo relativo, prévia do texto, aviso quando não detectou fala) e detalhes à direita (data, botões de copiar, texto final e transcrição original).
- **Configurações:** tela separada com "Voltar ao app", busca, e seções com título e subtítulo: Perfil, Geral, Atalhos, Voz e captura, Texto, Histórico, Privacidade.
- Outras seções do menu: Voice Engine, AI Enhancements, Insights, Custom Dictionary.

O que aproveitar no Arara (proposta, a confirmar):

- Barra lateral + conteúdo, tema escuro.
- Home com status do que está pronto e o cartão de ditar (com a arara no lugar do botão de microfone).
- Histórico em duas colunas: lista + detalhes, com busca e botão de copiar.
- Configurações com seções de título + subtítulo.
- Menu enxuto na v1: Início, Histórico, Dicionário, Configurações. Sem "AI Enhancements" e "Insights" por enquanto.
- Todos os textos em PT-BR.
- Ainda sem dono: origem do ditado (nome do app) no histórico, e guardar "texto final" separado do "original" (só faz sentido se houver pós-processamento, como o dicionário).

### Identidade visual (a definir)

- **Botões bem arredondados** (formato pílula). Cartões com cantos grandes.
- **Cores que lembram o Brasil**, próximas das cores da arara: verde (tom de maçã verde) como cor principal, azul como contraste, amarelo como apoio. As mesmas cores valem para a futura landing page.
- Texto escuro sobre os botões verdes, texto branco sobre os azuis. Todo par texto/fundo precisa de contraste de pelo menos 4.5:1.
- **Paleta escolhida: A · Canindé** (2026-11-13). Prévia em `arte/paletas/paleta-a-caninde.png`, gerada por `arte/gerar_paletas.py`. B e C descartadas.

| Token | Hex | Uso |
|---|---|---|
| Verde | `#6CC93A` | Cor principal: botão principal, estado "Pronto", barra de áudio |
| Verde claro | `#9BE36A` | Hover e destaques do verde |
| Azul | `#1C72CF` | Contraste: botões secundários, estado "Ouvindo" |
| Amarelo | `#FFC93C` | Apoio: estado "Transcrevendo", avisos |
| Fundo | `#0F1712` | Fundo da janela |
| Superfície | `#18221B` | Cartões e barra lateral |
| Borda | `#2A3A2E` | Bordas e divisórias |
| Texto | `#EEF5EC` | Texto principal |
| Texto suave | `#9AAE9E` | Subtítulos e textos secundários |
| Texto escuro | `#0B140D` | Texto sobre verde e amarelo |

Contraste: texto escuro sobre verde 9.0:1, branco sobre azul 4.8:1, texto sobre fundo 16.4:1.
- **Tipografia: Nunito Sans** (licença OFL). Arredondada, combina com os botões em pílula.
- **Ícones: Lucide** (licença ISC), traço fino.

## Melhorias futuras

- Aceleração via CUDA.
- Suporte a Linux e macOS.
- Testar o modelo Parakeet v3 (via sherpa-onnx) como alternativa ao Whisper.

## Pendências

- [x] Nome: Arara.
- [x] Receber a referência do polvo 8-bit.
- [x] Espécie: arara-canindé. Rascunho v1 em `arte/` (gerado por `arte/gerar_arara.py`).
- [x] Visual escolhido: v3, mais cartunizada (cabeça grande, olhos grandes, asas abertas), baseada numa imagem gerada no Gemini. Grade 31×42 em `arte/arara-v3.png`, gerada por `arte/gerar_arara.py`. v1 e v2 descartadas.
- [ ] Criar os sprites da arara (ocioso, escutando, silêncio, transcrevendo).
- [ ] Verificar a manutenção do `whisper-jni` e o suporte dele a Vulkan.
- [x] Paleta de cores: A · Canindé. Botões em pílula.
- [x] Tipografia: Nunito Sans. Ícones: Lucide.
- [ ] Desenhar as telas no FigCraft: Início, Histórico, Configurações e o pop-up de gravação. Rascunho V1 (2026-11-13) em `design/arara-v1.figcraft`: Estilo, Início, Histórico, Dicionário, Configurações e Pop-up. Falta revisar e aprovar.
- [ ] Definir o atalho padrão.
- [x] Limite de 5 min de silêncio: transcreve, salva no histórico e cola.
- [x] Histórico: JSON Lines, sem limite de itens, local e por usuário.
- [ ] Medir a latência real em CPU e em Vulkan.
