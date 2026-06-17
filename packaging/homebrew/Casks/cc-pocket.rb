# Homebrew CASK for the cc-pocket daemon (a prebuilt, notarized binary — NOT a buildable formula,
# so it needs no Command Line Tools). Lives in the tap repo: heypandax/homebrew-tap → Casks/cc-pocket.rb
# Users: brew install --cask heypandax/tap/cc-pocket
cask "cc-pocket" do
  version "1.1.2"
  sha256 "b9a99fdabe8dbba3d9863d75c64574a8d8d7f5e62c978497e5ed81996a48277c"

  url "https://github.com/heypandax/homebrew-tap/releases/download/v#{version}/cc-pocket-daemon-#{version}-macos-arm64.tar.gz"
  name "CC Pocket daemon"
  desc "Drive Claude Code on your Mac from your phone over a zero-knowledge E2E relay"
  homepage "https://github.com/heypandax/cc-pocket"

  depends_on arch: :arm64

  # the launcher lives in a self-contained .app (bundled JRE); symlink it onto PATH
  binary "cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon"

  # Install the login service right away so the daemon auto-starts on every boot and auto-reconnects
  # with no extra command. service-install writes ~/Library/LaunchAgents/dev.ccpocket.daemon.plist
  # (RunAtLoad + KeepAlive) pointing at the symlink above, with a PATH that can find `claude`.
  postflight do
    system_command "#{HOMEBREW_PREFIX}/bin/cc-pocket-daemon",
                   args: ["service-install", "--apply"]
  end

  # Stop the login service on uninstall.
  uninstall launchctl: "dev.ccpocket.daemon"

  # `brew uninstall --zap` also wipes the agent, logs, and the daemon identity/pairings.
  zap trash: [
    "~/Library/LaunchAgents/dev.ccpocket.daemon.plist",
    "~/Library/Logs/cc-pocket",
    "~/.cc-pocket",
  ]

  caveats <<~EOS
    cc-pocket drives Claude Code, so install + sign in to it first:
      https://claude.com/claude-code   (run `claude` once to authenticate)

    The daemon was installed as a login service — it auto-starts on boot and reconnects.
    Just pair your phone:
      cc-pocket-daemon pair                        # shows a QR + 6-digit code

    Logs:  ~/Library/Logs/cc-pocket/daemon.err.log

    Optional — voice input from Android/desktop clients transcribes on this Mac:
      brew install whisper-cpp
  EOS
end
