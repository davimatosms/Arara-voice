# Como inscrever o Arara na SignPath Foundation

A SignPath Foundation dá assinatura de código grátis para projetos open source. Com ela, o Windows para de mostrar "Editor desconhecido" no instalador do Arara. Este guia é para o dono do repositório fazer a inscrição e, depois da aprovação, ligar a assinatura no GitHub Actions.

Fontes: condições em https://signpath.org/terms, formulário em https://signpath.org/apply, integração com o GitHub em https://docs.signpath.io/trusted-build-systems/github e configuração de artefatos em https://docs.signpath.io/artifact-configuration/examples.

## 1. Antes de se inscrever

Faça nesta ordem.

1. **Ligue a autenticação em dois fatores (MFA) no GitHub.** A SignPath exige MFA de todos da equipe, no GitHub e na SignPath. No GitHub: foto do perfil, **Settings**, **Password and authentication**, **Enable two-factor authentication**. Use um app autenticador ou uma passkey e guarde os códigos de recuperação. Página de ajuda: https://docs.github.com/pt/authentication/securing-your-account-with-two-factor-authentication-2fa/configuring-two-factor-authentication
2. **Confira que o repositório é público** e que tem o `LICENSE` (MIT) e o `THIRD_PARTY_NOTICES.md` na `main`.
3. **Publique a página da política.** A página `site/politica.html` precisa estar no ar no site do projeto. A SignPath exige que o termo "Code signing policy" apareça na página inicial e na página de download. A landing já tem o link no rodapé. Coloque também o link no texto de cada release do GitHub, por exemplo: `Code signing policy: https://<site>/politica.html`.
4. **Tenha uma release publicada.** A SignPath só aceita projetos que já tenham lançado o que vão assinar. Uma release no GitHub com o instalador sem assinatura basta.
5. **Proteja a `main`** (recomendado, porque a SignPath pede revisão de mudanças de quem não é da equipe): **Settings**, **Branches**, **Add branch ruleset** e exija pull request para a `main`.

## 2. O formulário (https://signpath.org/apply)

O formulário abre no navegador. Os campos podem mudar, mas em geral pedem o seguinte.

| Campo | O que preencher |
|---|---|
| Nome do projeto | Arara |
| Descrição | Ditado por voz local e open source para o português do Brasil, no Windows. Roda o Whisper (whisper.cpp) no PC do usuário: o áudio e o texto não saem da máquina. |
| Repositório | https://github.com/davimatosms/Arara-voice |
| Licença | MIT |
| Página do projeto | o endereço da landing (o site em `site/`, publicado na Vercel) |
| Code signing policy | `https://<site>/politica.html` |
| Download | https://github.com/davimatosms/Arara-voice/releases |
| Build system | GitHub Actions (runners hospedados pelo GitHub) |
| O que será assinado | o instalador `.exe` (Inno Setup) e o `Arara.exe` do app |
| Equipe | davimatosms: Author, Reviewer e Approver |
| Contato | o seu e-mail |
| Comentários / perguntas | cole a pergunta sobre o Inno Setup abaixo |

**Pergunta sobre o Inno Setup** (decidido: perguntar na inscrição em vez de trocar o instalador). Cole no campo de comentários ou mande junto com a inscrição:

> Our Windows installer is built with Inno Setup (https://jrsoftware.org/isinfo.php). Its license (https://jrsoftware.org/files/is/license.txt) is a permissive BSD-style license, but it is not on the OSI list, and the Inno Setup setup stub is embedded in the installer we would sign. All of our own code is MIT and every runtime dependency is OSI-licensed (see THIRD_PARTY_NOTICES.md). Is an Inno Setup installer acceptable under your terms, or should we switch to NSIS or a WiX-based MSI?

Se a resposta for não, a troca mais próxima do design aprovado é o NSIS (zlib, aprovada pela OSI).

## 3. O que esperar

- **Aprovação manual e sem prazo.** A SignPath avalia cada projeto e pode recusar sem explicar ("We're under no obligation to accept your project").
- **O editor será "SignPath Foundation".** O certificado é da fundação, então o Windows mostra "SignPath Foundation" como editor, não o seu nome.
- **Cada release precisa de aprovação manual** de um Approver no site da SignPath antes de ser assinada.
- **Os metadados do executável precisam bater.** O nome do produto tem que ser "Arara" e a versão tem que ser a mesma em todos os arquivos do build.
- **Todos os componentes precisam ter licença aprovada pela OSI.** Veja o ponto de atenção sobre o Inno Setup no fim deste guia.

## 4. Depois da aprovação

A SignPath cria uma organização para o projeto e manda o acesso. Faça o seguinte.

### 4.1 Na SignPath

1. Entre em https://app.signpath.io e ative o MFA na sua conta.
2. Em **Trusted Build Systems**, adicione o **GitHub.com** à organização e ligue ao projeto do Arara (https://docs.signpath.io/trusted-build-systems#configuration).
3. Instale o app do GitHub da SignPath no repositório: https://github.com/apps/signpath
4. Anote os quatro valores:
   - **Organization ID**, que fica em **Settings** da organização;
   - **slug do projeto**, por exemplo `arara`;
   - **slug da signing policy**, que é `release-signing` (a fundação costuma criar também `test-signing`);
   - **slug da artifact configuration**, opcional: sem ele vale a configuração padrão do projeto.
5. Crie um usuário de CI (**Users**, **Add CI user**) com permissão de *submitter* na signing policy e gere um **API token** para ele.
6. Confira a **artifact configuration**. O `actions/upload-artifact` sempre empacota em ZIP, então a raiz é `<zip-file>`. Para um ZIP com o instalador:

   ```xml
   <artifact-configuration xmlns="http://signpath.io/artifact-configuration/v1">
     <zip-file>
       <pe-file path="Arara-*.exe">
         <authenticode-sign/>
       </pe-file>
     </zip-file>
   </artifact-configuration>
   ```

   Para assinar também o `Arara.exe` que vai dentro do instalador, o workflow precisa assinar o app-image primeiro e só depois gerar o instalador. São dois pedidos de assinatura.

### 4.2 No GitHub

1. No repositório: **Settings**, **Secrets and variables**, **Actions**.
2. Em **Secrets**, crie `SIGNPATH_API_TOKEN` com o token do usuário de CI.
3. Em **Variables**, crie `SIGNPATH_ORGANIZATION_ID`, `SIGNPATH_PROJECT_SLUG` e `SIGNPATH_SIGNING_POLICY_SLUG` (e `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG`, se usar uma configuração que não é a padrão). Esses valores não são segredos, mas também podem ir como secrets.

### 4.3 Ligar o passo de assinatura no workflow

O workflow de release (`.github/workflows/`) já vem com o passo de assinatura preparado e desligado. Para ligar, siga o comentário do próprio workflow. O formato é o da documentação (https://docs.signpath.io/trusted-build-systems/github):

```yaml
- name: Envia o instalador sem assinatura
  id: upload-unsigned
  uses: actions/upload-artifact@v4
  with:
    name: instalador-sem-assinatura
    path: app/build/instalador/*.exe

- name: Assina com a SignPath
  uses: signpath/github-action-submit-signing-request@v3
  with:
    api-token: '${{ secrets.SIGNPATH_API_TOKEN }}'
    organization-id: '${{ vars.SIGNPATH_ORGANIZATION_ID }}'
    project-slug: '${{ vars.SIGNPATH_PROJECT_SLUG }}'
    signing-policy-slug: '${{ vars.SIGNPATH_SIGNING_POLICY_SLUG }}'
    github-artifact-id: '${{ steps.upload-unsigned.outputs.artifact-id }}'
    wait-for-completion: true
    output-artifact-directory: assinado
```

Regras que a SignPath confere:

- todos os jobs até o pedido de assinatura rodam em runners hospedados pelo GitHub (`runs-on: windows-latest`), não em máquina própria;
- o arquivo vai como artefato do workflow (`actions/upload-artifact` v4 ou mais nova) antes de ser assinado;
- com `wait-for-completion: true`, o job espera você aprovar o pedido no site da SignPath (o padrão é esperar 600 segundos; aumente com `wait-for-completion-timeout-in-seconds` se precisar de mais tempo para aprovar).

### 4.4 Primeira release assinada

1. Crie a tag da versão e deixe o workflow rodar.
2. Aprove o pedido em https://app.signpath.io (o link sai no log, na saída `signing-request-web-url`).
3. Baixe o instalador assinado e confira: botão direito, **Propriedades**, **Assinaturas digitais**. O editor tem que ser "SignPath Foundation".
4. No texto da release, mantenha o link para a Code signing policy.

## Pontos de atenção antes de mandar

- **Inno Setup:** o instalador é gerado com o Inno Setup, que embute o seu próprio executável de instalação no `.exe`. A licença do Inno Setup (https://jrsoftware.org/files/is/license.txt) é livre e no estilo BSD, mas não está na lista da OSI, e a SignPath pede licença OSI em todos os componentes. Muitos projetos assinados pela SignPath usam o Inno Setup, então pode não ser problema. Na dúvida, pergunte à SignPath na inscrição. A alternativa sem risco é trocar para o NSIS (zlib/libpng, aprovada pela OSI) ou para MSI com o WiX (MS-RL, aprovada pela OSI, e o `jpackage` já sabe gerar MSI com ele).
- **Desinstalação e "Iniciar com o Windows":** se o usuário ligar essa opção, a entrada em `HKCU\...\Run` precisa sair na desinstalação. Senão, o sistema fica com uma entrada apontando para um app que não existe mais. A SignPath pede que o software não altere o sistema sem aviso.
- **Aviso do download do modelo:** na primeira execução o app baixa o modelo da Hugging Face sem perguntar. Não é envio de dado do usuário, e a política descreve o download. Ainda assim, a tela de primeira execução e o instalador devem avisar que ele acontece.
