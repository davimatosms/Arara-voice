# Para o tap próprio davimatosms/homebrew-arara (repo GitHub "homebrew-arara", pasta Casks/).
# Só publique quando o .dmg sair assinado com Developer ID e notarizado: veja pacotes/README.md.
cask "arara" do
  arch arm: "AppleSilicon", intel: "Intel"

  version "0.4.0"
  sha256 arm:   "bb4760fcba5b829a0190f1fada06eef2194262437835c6b53846c32b6de75875",
         intel: "577d08f9eaa214621b20944b601c95e1e93548867d5d37b3aacb508410697ed3"

  url "https://github.com/davimatosms/Arara-voice/releases/download/v#{version}/Arara-#{version}-Mac-#{arch}.dmg"
  name "Arara"
  desc "Ditado por voz em português que roda 100% no seu computador"
  homepage "https://arara-voice.vercel.app/"

  livecheck do
    url :url
    strategy :github_latest
  end

  auto_updates true

  app "Arara.app"

  uninstall launchctl: "br.arara.Arara",
            delete:    "~/Library/LaunchAgents/br.arara.Arara.plist"

  zap trash: "~/Library/Application Support/Arara"
end
