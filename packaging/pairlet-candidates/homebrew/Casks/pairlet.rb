# Unpublished migration candidate; baseline hashes are from 1.9.7, not a Pairlet release.
# Homebrew CASK for the Pairlet daemon (a prebuilt, notarized binary — NOT a buildable formula,
# so it needs no Command Line Tools). The cask file lives in the tap repo
# (heypandax/homebrew-tap → Casks/cc-pocket.rb); the artifact (.tar.gz) is hosted on the MAIN repo's
# Releases (heypandax/cc-pocket) — the url below points there. The tap holds only this description.
# After acceptance: brew install --cask heypandax/tap/pairlet
cask "pairlet" do
  # Apple Silicon and Intel each get their own notarized build (jpackage bundles an arch-specific
  # JRE — see .github/workflows/release.yml). `arch` maps the running CPU to the asset suffix; the
  # sha256 differs per arch. Refresh BOTH after each release run (notarized tarballs aren't
  # bit-reproducible, so even the arm64 sha changes on a rebuild).
  arch arm: "arm64", intel: "x86_64"

  version "1.9.7"
  sha256 arm:   "34d0bb10352b99be2b0ac40aac22d854de5b4b5f6263b976ac4ede8bfb66273f",
         intel: "b9e66f07d8229b68a930fc61610a6af093e08417877b46fbbd279b25393c506e"

  url "https://github.com/heypandax/cc-pocket/releases/download/v#{version}/cc-pocket-daemon-#{version}-macos-#{arch}.tar.gz"
  name "Pairlet daemon"
  desc "Companion daemon for AI coding agents on your computer"
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
    Install and sign in to the coding-agent CLI you plan to use,
    for example Codex CLI or Claude Code.

    The daemon was installed as a login service — it auto-starts on boot and reconnects.
    Just pair your phone:
      pairlet pair                        # shows a QR + 6-digit code

    Logs:  ~/Library/Logs/cc-pocket/daemon.err.log

    Optional — voice input from Android/desktop clients transcribes on this Mac:
      brew install whisper-cpp
  EOS
end
