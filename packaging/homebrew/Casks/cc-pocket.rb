# Homebrew CASK for the cc-pocket daemon (a prebuilt, notarized binary — NOT a buildable formula,
# so it needs no Command Line Tools). The cask file lives in the tap repo
# (heypandax/homebrew-tap → Casks/cc-pocket.rb); the artifact (.tar.gz) is hosted on the MAIN repo's
# Releases (heypandax/cc-pocket) — the url below points there. The tap holds only this description.
# Users: brew install --cask heypandax/tap/cc-pocket
cask "cc-pocket" do
  # Apple Silicon and Intel each get their own notarized build (jpackage bundles an arch-specific
  # JRE — see .github/workflows/release.yml). `arch` maps the running CPU to the asset suffix; the
  # sha256 differs per arch. Refresh BOTH after each release run (notarized tarballs aren't
  # bit-reproducible, so even the arm64 sha changes on a rebuild).
  arch arm: "arm64", intel: "x86_64"

  version "1.8.0"
  sha256 arm:   "dc5cc841faee0eed3004b41ae924ebd5d1b91bff277e36d60827a0b9fa126593",
         intel: "2a1e6db80ab359c56db00932b76432c5b44f9fbc0231db61dce8ce2ecb5c7635"

  url "https://github.com/heypandax/cc-pocket/releases/download/v#{version}/cc-pocket-daemon-#{version}-macos-#{arch}.tar.gz"
  name "CC Pocket daemon"
  desc "Drive Claude Code from your phone over a zero-knowledge E2E relay"
  homepage "https://github.com/heypandax/cc-pocket"

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
    "~/.cc-pocket",
    "~/Library/LaunchAgents/dev.ccpocket.daemon.plist",
    "~/Library/Logs/cc-pocket",
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
