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

  version "2.4.0"
  sha256 arm:   "52f0ad740b61839ea8c0864dfc4f3da6da1e3ea4c2bc41b3ea0f8f6bbefc8f31",
         intel: "b20a8ca517ba1fff8ab4680c0d8987c26854af237fa50ef61bcc4177afbbfb8c"

  url "https://github.com/heypandax/cc-pocket/releases/download/v#{version}/cc-pocket-daemon-#{version}-macos-#{arch}.tar.gz"
  name "CC Pocket daemon"
  desc "Drive Claude Code from your phone over a zero-knowledge E2E relay"
  homepage "https://github.com/heypandax/cc-pocket"

  # the launcher lives in a self-contained .app (bundled JRE); symlink it onto PATH
  binary "cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon"
  binary "cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon", target: "pairlet"

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
      pairlet pair                                # shows a QR + 6-digit code
      # cc-pocket-daemon remains available with the same subcommands.

    Logs:  ~/Library/Logs/cc-pocket/daemon.err.log

    Optional — voice input from Android/desktop clients transcribes on this Mac:
      brew install whisper-cpp
  EOS
end
