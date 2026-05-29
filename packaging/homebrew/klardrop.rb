# This is a TEMPLATE. The release workflow renders @VERSION@ and @SHA256@ from the
# git tag and the DMG checksum, then pushes the result to the Homebrew tap
# (carlonzo/homebrew-klardrop) as Casks/klardrop.rb. Users then install with
# `brew install --cask carlonzo/klardrop/klardrop`. To test locally: replace the
# two placeholders by hand, point url at a real release DMG, then run
# `brew install --cask ./klardrop.rb`.
#
# The macOS DMG is currently UNSIGNED (no Apple Developer ID). Gatekeeper would
# otherwise refuse to launch it, so the postflight below strips the quarantine
# attribute that Homebrew applies on install, and the caveats explain the manual
# fallback. This lives in a third-party tap, so it is not subject to the official
# homebrew-cask Gatekeeper audit.

cask "klardrop" do
  version "@VERSION@"
  sha256 "@SHA256@"

  url "https://github.com/carlonzo/klardrop/releases/download/v#{version}/klardrop-#{version}.dmg",
      verified: "github.com/carlonzo/klardrop/"
  name "Klardrop"
  desc "Share files and clipboard with nearby devices over the local network (AirDrop-style)"
  homepage "https://github.com/carlonzo/klardrop"

  app "klardrop.app"

  # The app is unsigned, so Homebrew's quarantine attribute would make macOS
  # Gatekeeper block the first launch. Strip it from the installed bundle. This
  # uses the standard cask DSL (system_command in a postflight block) and does
  # NOT rely on the deprecated --no-quarantine flag.
  postflight do
    system_command "/usr/bin/xattr",
                   args: ["-dr", "com.apple.quarantine", "#{appdir}/klardrop.app"],
                   sudo: false
  end

  caveats <<~EOS
    Klardrop is not signed with an Apple Developer ID, so macOS Gatekeeper may
    still refuse to open it on first launch.

    The install above already tried to clear the quarantine flag for you. If
    macOS still reports that the app "cannot be opened", do one of:

      * Right-click (or Control-click) #{appdir}/klardrop.app in Finder and
        choose Open, then confirm in the dialog; or
      * Clear the quarantine flag manually:
          xattr -dr com.apple.quarantine "#{appdir}/klardrop.app"
  EOS

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
