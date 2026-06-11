# This is a TEMPLATE. The release workflow renders @VERSION@ and @SHA256@ from the
# git tag and the DMG checksum, then pushes the result to the Homebrew tap
# (carlonzo/homebrew-klardrop) as Casks/klardrop.rb. Users then install with
# `brew install --cask carlonzo/klardrop/klardrop`. To test locally: replace the
# two placeholders by hand, point url at a real release DMG, then run
# `brew install --cask ./klardrop.rb`.
#
# The macOS DMG shipped via this cask is a Developer ID-signed + notarized native
# macOS app (built from Swift/SKIE, not the legacy Compose/JVM desktop). The
# Homebrew job only publishes this cask when the release contains a macos-verified.txt
# marker (written by the notarize step), guaranteeing this cask always points at
# a properly signed and notarized build.

cask "klardrop" do
  version "@VERSION@"
  sha256 "@SHA256@"

  url "https://github.com/carlonzo/klardrop/releases/download/v#{version}/klardrop-#{version}.dmg",
      verified: "github.com/carlonzo/klardrop/"
  name "Klardrop"
  desc "Share files and clipboard with nearby devices over the local network (AirDrop-style)"
  homepage "https://github.com/carlonzo/klardrop"

  app "Klardrop.app"

  zap trash: [
    "~/Library/Application Support/com.carlom.Klardrop",
    "~/Library/Application Support/klardrop",
    "~/Library/Caches/com.carlom.Klardrop",
    "~/Library/Preferences/com.carlom.Klardrop.plist",
    "~/Library/Saved Application State/com.carlom.Klardrop.savedState",
    "~/Library/Logs/klardrop",
    "~/.klardrop",
  ]
end
