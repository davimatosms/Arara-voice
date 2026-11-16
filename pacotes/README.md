# Gerenciadores de pacote

Arquivos prontos para publicar o Arara no winget, no Scoop e (quando houver assinatura Apple) no Homebrew.
Nada aqui é enviado sozinho: cada canal precisa de uma ação do dono do repo.

| Canal | Aceita o Arara hoje (sem assinatura)? | Arquivos |
|---|---|---|
| winget (Windows) | Sim | `winget/manifests/d/davimatosms/Arara/<versão>/` |
| Scoop (Windows) | Sim, num bucket próprio. O bucket `extras` não aceita hoje | `scoop/arara.json` |
| Homebrew (Mac) | Não no repositório oficial. Cask pronta para quando houver Developer ID | `homebrew/Casks/arara.rb` |

## winget

**Regras que valem para o Arara** (em novembro de 2026):

- O winget-pkgs não exige assinatura de código. O pipeline baixa o instalador, confere o SHA-256, passa antivírus (Defender e outros), instala e desinstala em silêncio, e checa a reputação das URLs no SmartScreen. As URLs do GitHub Releases passam.
- Se o Defender acusar falso positivo, a etiqueta é `Validation-Defender-Error` ou `Binary-Validation-Error`. O caminho é enviar o `.exe` em https://www.microsoft.com/wdsi/filesubmission e comentar no PR.
- Instalador Inno Setup por usuário: `InstallerType: inno` e `Scope: user`. O winget já sabe os switches do Inno (`/SP- /VERYSILENT /SUPPRESSMSGBOXES /NORESTART`), então o manifest não repete `InstallerSwitches`. Em modo silencioso o instalador não abre o app (`skipifsilent`).
- `UpgradeBehavior: install`: a versão nova instala por cima, como o próprio app já faz.
- `ProductCode` é o do Inno (`{AppId}_is1`). Ele liga o pacote à entrada do Arara em "Aplicativos instalados", inclusive para quem instalou pelo site. Por isso `winget upgrade` também enxerga essas instalações.
- `PackageIdentifier: davimatosms.Arara`, `Moniker: arara`. Os dois estavam livres em 16/11/2026 (`winget search Arara` e a pasta `manifests/d/davimatosms` não existem).
- Manifest 1.10.0, o mais usado nos PRs aceitos hoje. `winget validate` (winget v1.29) passa.

### 1ª vez (manual, uma vez só)

1. Crie um token em https://github.com/settings/tokens: "classic", escopo `public_repo`. Guarde-o.
2. No Windows, instale o wingetcreate (`winget install Microsoft.WingetCreate`).
3. Na raiz deste repo:

   ```powershell
   winget validate --manifest pacotes\winget\manifests\d\davimatosms\Arara\0.4.0
   wingetcreate submit --token <TOKEN> pacotes\winget\manifests\d\davimatosms\Arara\0.4.0
   ```

   O `wingetcreate submit` faz o fork do `microsoft/winget-pkgs` na sua conta e abre o PR. O PR é público, sai no seu nome.
   Sem wingetcreate: faça o fork no site, copie a pasta `0.4.0` para `manifests/d/davimatosms/Arara/0.4.0` e abra o PR com o título `New package: davimatosms.Arara version 0.4.0`.
4. **O que acontece no PR:** um bot põe etiquetas. `Azure-Pipeline-Passed` é bom sinal. `Needs-Author-Feedback` pede ação sua em até 10 dias, senão o PR fecha. Pacote novo sempre passa por um moderador. O tempo típico vai de 1 a 7 dias. Depois do merge, `winget install arara` funciona em algumas horas.

### Versões seguintes (automático, depois da 1ª aceita)

O workflow `.github/workflows/pacotes.yml` abre sozinho o PR de cada versão nova no winget-pkgs quando uma release é publicada. Ele usa a action `vedantmgoyal9/winget-releaser`, que roda o komac. Fica desligado até você:

1. Manter o fork `<sua conta>/winget-pkgs` (o passo 3 acima já o cria).
2. Criar o secret do repo: Settings, Secrets and variables, Actions, New repository secret. Nome `WINGET_TOKEN`, valor: o token `public_repo` acima.

A action pega só o `Arara-Setup-<versão>.exe`, não a cópia `Arara-Setup.exe`. Se a release for publicada antes de os arquivos subirem, rode o workflow à mão (Actions, pacotes, Run workflow, tag `vX.Y.Z`). Atualização de versão costuma ser aprovada sem moderador, em horas.

Sem o secret, faça à mão: `wingetcreate update davimatosms.Arara --version <versão> --urls https://github.com/davimatosms/Arara-voice/releases/download/v<versão>/Arara-Setup-<versão>.exe --submit --token <TOKEN>`.

## Scoop

**O bucket `extras` não aceita o Arara hoje.** O pedido (issue "Package Request", obrigatória antes do PR) exige um projeto "razoavelmente conhecido", com pelo menos 100 estrelas e/ou 50 forks no GitHub, e interface ou documentação em inglês. O Arara tem interface só em português e poucas estrelas. Assinatura de código e instalador Inno não são problema: o `extras` usa `"innosetup": true` em muitos apps.

**Bucket próprio (funciona hoje):**

1. Crie o repo público `davimatosms/scoop-arara` a partir do modelo https://github.com/ScoopInstaller/BucketTemplate ("Use this template"). O modelo já traz o Excavator, um workflow que roda o `checkver` a cada poucas horas e faz commit da versão nova sozinho.
2. Copie `pacotes/scoop/arara.json` para `bucket/arara.json` nesse repo e faça o commit.
3. Quem quiser instalar:

   ```powershell
   scoop bucket add arara https://github.com/davimatosms/scoop-arara
   scoop install arara/arara
   ```

O manifest extrai o instalador Inno com `innosetup: true`, sem executar o Setup. O app fica em `~\scoop\apps\arara\current`, com atalho no menu Iniciar. `checkver` lê a última release do GitHub e `autoupdate` monta a URL `Arara-Setup-$version.exe`; o hash é calculado baixando o arquivo. Atenção: o aviso de versão nova do próprio Arara instalaria uma segunda cópia em `%LOCALAPPDATA%\Programs`. Quem usa Scoop deve atualizar com `scoop update arara` (está nas `notes` do manifest).

Se um dia o Arara passar dos critérios do `extras`: abra a issue "Package Request" em https://github.com/ScoopInstaller/Extras/issues, espere o ok e mande o mesmo JSON em `bucket/arara.json`, com o título `arara: Add version 0.4.0` e um comentário `/verify`.

## Homebrew (Mac)

**O homebrew-cask oficial não aceita o Arara hoje.** O Homebrew 5.0.0 (12/11/2025) tornou obsoletas as casks que falham na checagem do Gatekeeper e marcou para desativá-las em setembro de 2026 (`disable_date` 2026-09-01). O 6.0.0 (11/06/2026) confirmou o prazo. A regra atual (docs.brew.sh/Acceptable-Casks): apps que o Gatekeeper avalia "precisam passar nas checagens de Gatekeeper do Homebrew", e o app não pode exigir desligar ou contornar o Gatekeeper. O `.dmg` do Arara tem só assinatura ad-hoc e não é notarizado, então falha. Além disso, cask nova no repo oficial passa por regra de notabilidade (estrelas, forks e observadores no GitHub), mais rígida quando o próprio autor envia.

**Tap próprio:** `homebrew/Casks/arara.rb` está pronta para o repo `davimatosms/homebrew-arara`. Publique-a só depois que o `.dmg` sair assinado com Developer ID e notarizado. O passo desligado em `.github/workflows/macos.yml` já faz isso quando existirem os secrets `APPLE_*`; precisa da conta Apple Developer (US$ 99/ano). Antes disso, a cask até instalaria, mas o macOS bloquearia o app na primeira abertura do mesmo jeito que o `.dmg` baixado do site. O Homebrew está tirando o `--no-quarantine`, e o tap não aparece no `brew search` de ninguém, então não vale a pena.

Quando houver assinatura:

1. Crie o repo público `davimatosms/homebrew-arara` com `Casks/arara.rb` (copie daqui e confira os dois `sha256` com os da release).
2. Quem quiser instalar:

   ```sh
   brew install --cask davimatosms/arara/arara
   ```

   O Homebrew 6 pede para confiar no tap na primeira vez.
3. A cada versão, troque `version` e os dois `sha256` (eles estão no fim das notas da release). Ou rode `brew bump-cask-pr --version <versão> davimatosms/arara/arara`.
4. Com o app assinado e o projeto mais conhecido, dá para propor no oficial: PR em https://github.com/Homebrew/homebrew-cask com o mesmo arquivo em `Casks/a/arara.rb`, depois de `brew audit --new --cask arara` e `brew style`.

## A cada release

Depois de publicar a release com o `Arara-Setup-<versão>.exe` e os `.dmg`:

- **winget:** automático com `WINGET_TOKEN`; sem ele, o comando `wingetcreate update` acima. Atualize também `pacotes/winget/manifests/.../<versão>/` neste repo só se quiser guardar uma cópia; o oficial é o do winget-pkgs.
- **Scoop:** nada a fazer. O Excavator do `scoop-arara` pega a versão nova em poucas horas. Mantenha `pacotes/scoop/arara.json` igual ao do bucket se mudar algo além da versão.
- **Homebrew:** quando existir o tap, troque `version` e `sha256` em `Casks/arara.rb`.
