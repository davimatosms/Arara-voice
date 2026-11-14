; Instalador do Arara (Inno Setup 6.7+). Gere com ./gradlew :app:instalador.
; O Gradle passa /DAppVersion e /DAppImage (pasta do app-image do jpackage).
; Design: design/arara-v1.figcraft, página "10 · Instalador".

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef AppImage
  #define AppImage "..\app\build\jpackage\Arara"
#endif

[Setup]
AppId={{6B1E3C1A-4F7D-4E52-9A0B-A7A7A0A0B001}
AppName=Arara
AppVersion={#AppVersion}
AppVerName=Arara {#AppVersion}
AppPublisher=Arara
AppPublisherURL=https://github.com/davimatosms/Arara-voice
VersionInfoVersion={#AppVersion}
; Por usuário, sem UAC: {autopf} vira %LOCALAPPDATA%\Programs
PrivilegesRequired=lowest
DefaultDirName={autopf}\Arara
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Tela única: Boas-vindas com o botão "Instalar", depois progresso e fim
DisableWelcomePage=no
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
WizardStyle=modern dark
WizardBackColor=#0F1712
WizardImageFile=assets\arara-lateral-100.png,assets\arara-lateral-125.png,assets\arara-lateral-150.png,assets\arara-lateral-175.png,assets\arara-lateral-200.png,assets\arara-lateral-225.png,assets\arara-lateral-250.png
WizardSmallImageFile=assets\arara-pequena-100.png,assets\arara-pequena-125.png,assets\arara-pequena-150.png,assets\arara-pequena-175.png,assets\arara-pequena-200.png,assets\arara-pequena-225.png,assets\arara-pequena-250.png
SetupIconFile=assets\arara.ico
UninstallDisplayIcon={app}\Arara.exe
UninstallDisplayName=Arara
OutputBaseFilename=Arara-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[LangOptions]
DialogFontName=Segoe UI
DialogFontSize=9
WelcomeFontName=Segoe UI
WelcomeFontSize=14

[Messages]
SetupWindowTitle=Instalação — %1
WelcomeLabel1=Ditado por voz em português.
WelcomeLabel2=Roda 100% no seu PC. Sua voz nunca sai daqui.%n%nO Arara vai ser instalado só para você, sem pedir senha de administrador. Leva menos de um minuto.%n%nClique em Instalar para começar.%n%n%n~45 MB agora · modelo de voz depois
InstallingLabel=Aguarde enquanto o [name] é instalado no seu computador.
StatusExtractFiles=Extraindo arquivos…
FinishedHeadingLabel=Tudo pronto!
FinishedLabel=O Arara foi instalado no seu computador.%n%nNa primeira vez ele baixa o modelo de voz (~574 MB) e te ajuda a testar o microfone e o atalho.
FinishedLabelNoIcons=O Arara foi instalado no seu computador.%n%nNa primeira vez ele baixa o modelo de voz (~574 MB) e te ajuda a testar o microfone e o atalho.
UninstalledAll=O %1 foi removido do seu computador.%n%nSuas configurações, histórico e modelos de voz continuam em AppData\Roaming\Arara, para quando você instalar de novo. Apague essa pasta se não quiser guardá-los.

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Imagens do fim ("TUDO PRONTO!"), carregadas em [Code]
Source: "assets\arara-lateral-fim-*.png"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\Arara"; Filename: "{app}\Arara.exe"

[Run]
Filename: "{app}\Arara.exe"; Description: "Abrir o Arara agora"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; O app fica na bandeja; fecha antes de apagar os arquivos
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM Arara.exe"; Flags: runhidden; RunOnceId: "FecharArara"

; %APPDATA%\Arara (dados e modelos) fica de propósito: nada em [UninstallDelete].

[Code]
const
  Escalas = '100,125,150,175,200,225,250';
  Larguras = '202,269,336,403,430,498,534';

{ Mesma regra do WizardImageFile: a menor imagem que cobre o controle já escalado pelo DPI. }
procedure TrocarImagemDoFim;
var
  E, L: TStringList;
  I: Integer;
  Nome: String;
  Png: TPngImage;
begin
  E := TStringList.Create;
  L := TStringList.Create;
  try
    E.CommaText := Escalas;
    L.CommaText := Larguras;
    I := 0;
    while (I < E.Count - 1) and (StrToInt(L[I]) < WizardForm.WizardBitmapImage2.Width) do
      I := I + 1;
    Nome := 'arara-lateral-fim-' + E[I] + '.png';
    ExtractTemporaryFile(Nome);
    Png := TPngImage.Create; { fica com o controle até o fim do Setup }
    Png.LoadFromFile(ExpandConstant('{tmp}\') + Nome);
    WizardForm.WizardBitmapImage2.PngImage := Png;
  finally
    E.Free;
    L.Free;
  end;
end;

procedure InitializeWizard;
begin
  TrocarImagemDoFim;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpWelcome then
    WizardForm.NextButton.Caption := SetupMessage(msgButtonInstall);
end;
