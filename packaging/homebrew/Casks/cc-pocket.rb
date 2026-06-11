# Homebrew CASK for the cc-pocket daemon (a prebuilt, notarized binary — NOT a buildable formula,
# so it needs no Command Line Tools). Lives in the tap repo: heypandax/homebrew-tap → Casks/cc-pocket.rb
# Users: brew install --cask heypandax/tap/cc-pocket
cask "cc-pocket" do
  version "1.1.0"
  sha256 "cc168733226dcb423cd8cafa3357f0d2dbaa74ab863b18cece7b266701723174"

  url "https://github.com/heypandax/homebrew-tap/releases/download/v#{version}/cc-pocket-daemon-#{version}-macos-arm64.tar.gz"
  name "CC Pocket daemon"
  desc "Drive Claude Code on your Mac from your phone over a zero-knowledge E2E relay"
  homepage "https://github.com/heypandax/cc-pocket"

  depends_on arch: :arm64

  # the launcher lives in a self-contained .app (bundled JRE); symlink it onto PATH
  binary "cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon"

  caveats <<~EOS
    cc-pocket drives Claude Code, so install + sign in to it first:
      https://claude.com/claude-code   (run `claude` once to authenticate)

    Start the daemon and pair your phone:
      cc-pocket-daemon service-install --apply   # run on login, auto-reconnect
      cc-pocket-daemon pair                        # shows a QR + 6-digit code

    Optional — voice input from Android/desktop clients transcribes on this Mac:
      brew install whisper-cpp
  EOS
end
