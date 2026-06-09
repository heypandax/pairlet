# Homebrew formula for the cc-pocket daemon (the Mac side).
# Lives in the tap repo: github.com/heypandax/homebrew-tap → Formula/cc-pocket.rb
# Users then: brew install heypandax/tap/cc-pocket
#
# The artifacts are self-contained (bundled JRE) + notarized, attached to the GitHub release.
# After each release: bump `version`, update both URLs + sha256 (printed by scripts/release-macos.sh).
class CcPocket < Formula
  desc "Drive Claude Code on your Mac from your phone over a zero-knowledge E2E relay"
  homepage "https://github.com/heypandax/cc-pocket"
  version "1.0.0"
  license "MIT"

  on_arm do
    # binaries are hosted on the public tap repo's releases (the main source repo is private)
    url "https://github.com/heypandax/homebrew-tap/releases/download/v1.0.0/cc-pocket-daemon-1.0.0-macos-arm64.tar.gz"
    sha256 "ec72f09ee50459565d5623cb5b23184994c5fbea5f352874ab83ed7af890431c"
  end
  on_intel do
    # v1.0.0 ships Apple Silicon only; build + add an x86_64 artifact to enable Intel.
    odie "cc-pocket v1.0.0 is Apple Silicon only for now — an Intel build is coming."
  end

  def install
    libexec.install "cc-pocket-daemon.app"
    (bin/"cc-pocket-daemon").write <<~SH
      #!/bin/bash
      exec "#{libexec}/cc-pocket-daemon.app/Contents/MacOS/cc-pocket-daemon" "$@"
    SH
    chmod 0o755, bin/"cc-pocket-daemon"
  end

  def caveats
    <<~EOS
      cc-pocket drives Claude Code, so install + sign in to it first:
        https://claude.com/claude-code      (then run `claude` once to authenticate)

      Start the daemon and pair your phone:
        cc-pocket-daemon service-install --apply   # runs on login, auto-reconnects
        cc-pocket-daemon pair                       # prints a QR + 6-digit code

      Open CC Pocket on your phone and scan the code.
      Uninstall the background service: launchctl unload ~/Library/LaunchAgents/dev.ccpocket.daemon.plist
    EOS
  end

  test do
    assert_match "cc-pocket-daemon", shell_output("#{bin}/cc-pocket-daemon --help 2>&1", 0)
  end
end
