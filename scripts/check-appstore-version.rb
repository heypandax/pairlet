#!/usr/bin/env ruby
# Read-only preflight: deliver's ensure_version! can otherwise renumber an existing version.
require "rubygems/version"

module AppStoreVersionGuard
  RELEASED = %w[READY_FOR_DISTRIBUTION READY_FOR_SALE].freeze
  EDITABLE = %w[PREPARE_FOR_SUBMISSION DEVELOPER_REJECTED REJECTED METADATA_REJECTED].freeze

  def self.check!(target:, versions:, editable:)
    raise "expected x.y.z version" unless target.match?(/\A\d+\.\d+\.\d+\z/)
    latest = versions.max_by { |version| Gem::Version.new(version[:number]) }
    raise "existing iOS version not found; this workflow is for updates" unless latest
    requested = versions.find { |version| version[:number] == target }

    if requested
      raise "target #{target} is not an editable draft (#{requested[:state]})" unless EDITABLE.include?(requested[:state])
      raise "target #{target} is not the latest iOS version" unless requested == latest
      raise "editable version is #{editable.inspect}, expected #{target}" unless editable == target
      :update
    else
      raise "another editable version exists: #{editable}; refusing to renumber it" if editable
      raise "latest version #{latest[:number]} has not been released (#{latest[:state]})" unless RELEASED.include?(latest[:state])
      raise "target must be newer than #{latest[:number]}" unless Gem::Version.new(target) > Gem::Version.new(latest[:number])
      :create
    end
  end
end

if $PROGRAM_NAME == __FILE__
  require "spaceship"
  Spaceship::ConnectAPI.token = Spaceship::ConnectAPI::Token.create(
    key_id: ENV.fetch("ASC_KEY_ID"), issuer_id: ENV.fetch("ASC_ISSUER_ID"),
    filepath: File.join(ENV.fetch("RUNNER_TEMP"), "AuthKey.p8"),
  )
  app = Spaceship::ConnectAPI::App.find("com.panda.ccpocket") or abort("ASC app not found")
  versions = app.get_app_store_versions(filter: { platform: "IOS" }, includes: nil).map do |version|
    { number: version.version_string, state: version.app_store_state }
  end
  editable = app.get_edit_app_store_version(platform: "IOS")&.version_string
  action = AppStoreVersionGuard.check!(target: ENV.fetch("VERSION"), versions: versions, editable: editable)
  puts("Allowed to #{action} iOS #{ENV.fetch('VERSION')}; no other version will be renamed or withdrawn")
end
