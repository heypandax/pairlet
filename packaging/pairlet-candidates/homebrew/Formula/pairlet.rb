class Pairlet < Formula
  desc "Control local AI coding agents from your phone"
  homepage "https://pairlet.org/"
  url "https://github.com/heypandax/pairlet/archive/refs/tags/v1.9.8.tar.gz"
  sha256 "39286a98ca022387d5b6b9d35b6ae2d839368c140c5787059528e8d844e4f389"
  license "MIT"
  head "https://github.com/heypandax/pairlet.git", branch: "main"

  depends_on "gradle@8" => :build
  depends_on "openjdk@17"

  def install
    ENV["JAVA_HOME"] = Language::Java.java_home("17")
    system "gradle", "--no-daemon", "--configure-on-demand", "--no-build-cache",
                     "-PappVersion=#{version}", ":daemon:installDist"

    libexec.install Dir["daemon/build/install/cc-pocket-daemon/*"]
    rm(libexec.glob("bin/*.bat"))
    rm(libexec/"bin/pairlet") if (libexec/"bin/pairlet").exist?
    env = Language::Java.java_home_env("17")
    (bin/"pairlet").write_env_script libexec/"bin/cc-pocket-daemon", env
    (bin/"cc-pocket-daemon").write_env_script libexec/"bin/cc-pocket-daemon", env
  end

  service do
    name macos: "dev.ccpocket.daemon", linux: "cc-pocket-daemon"
    run [opt_bin/"pairlet", "run"]
    keep_alive true
    environment_variables PATH: std_service_path_env
    log_path var/"log/pairlet.log"
    error_log_path var/"log/pairlet.log"
  end

  test do
    ENV["JAVA_TOOL_OPTIONS"] = "-Duser.home=#{testpath}"
    ENV["CC_POCKET_IDENTITY"] = (testpath/".cc-pocket/identity.json").to_s

    assert_match version.to_s, shell_output("#{bin}/pairlet --version")
    output = shell_output("#{bin}/pairlet config --isolated-claude-auth on")
    assert_match "isolated-claude-auth: on", output
    assert_equal true, JSON.parse((testpath/".cc-pocket/prefs.json").read)["isolatedClaudeAuth"]
    assert_match "isolated-claude-auth: on", shell_output("#{bin}/cc-pocket-daemon config")
    system bin/"cc-pocket-daemon", "config", "--isolated-claude-auth", "off"
    assert_match "isolated-claude-auth: off", shell_output("#{bin}/pairlet config")
  end
end
