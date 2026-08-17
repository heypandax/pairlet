#!/usr/bin/env ruby
# Make the visible 6.5-inch screenshot set exact and idempotent. fastlane can briefly leave duplicate
# screenshot records while replacing an existing set; this keeps one copy of each desired checksum,
# uploads anything missing, removes every stale record, and restores the filename order.

require "digest"
require "spaceship"

version_string = ENV.fetch("VERSION")
token = Spaceship::ConnectAPI::Token.create(
  key_id: ENV.fetch("ASC_KEY_ID"),
  issuer_id: ENV.fetch("ASC_ISSUER_ID"),
  filepath: File.join(ENV.fetch("RUNNER_TEMP"), "AuthKey.p8"),
)
Spaceship::ConnectAPI.token = token

app = Spaceship::ConnectAPI::App.find("com.panda.ccpocket") or abort("ASC app not found")
platform = Spaceship::ConnectAPI::Platform.map("ios")
version = app.get_edit_app_store_version(platform: platform) or abort("no editable iOS version")
abort("editable version is #{version.version_string}, expected #{version_string}") unless version.version_string == version_string

shot_type = Spaceship::ConnectAPI::AppScreenshotSet::DisplayType::APP_IPHONE_65
localizations = version.get_app_store_version_localizations

%w[en-US zh-Hans].each do |locale|
  localization = localizations.find { |item| item.locale == locale } or abort("missing ASC locale #{locale}")
  paths = Dir["fastlane/screenshots/#{locale}/*.png"].sort
  abort("#{locale}: expected 6 local screenshots, got #{paths.size}") unless paths.size == 6
  checksums = paths.map { |path| Digest::MD5.file(path).hexdigest }

  shot_set = localization.get_app_screenshot_sets.find { |item| item.screenshot_display_type == shot_type }
  shot_set ||= localization.create_app_screenshot_set(attributes: { screenshotDisplayType: shot_type })
  current = Spaceship::ConnectAPI::AppScreenshotSet.get(app_screenshot_set_id: shot_set.id).app_screenshots

  used_ids = []
  keepers = checksums.map do |checksum|
    match = current.find do |item|
      item.complete? && item.source_file_checksum&.downcase == checksum && !used_ids.include?(item.id)
    end
    used_ids << match.id if match
    match
  end

  # Remove stale/duplicate records first so a full 10-slot set cannot block missing uploads.
  current.reject { |item| used_ids.include?(item.id) }.each(&:delete!)

  keepers.each_with_index do |item, index|
    next if item
    puts("#{locale}: uploading missing #{File.basename(paths[index])} to #{shot_type}")
    keepers[index] = shot_set.upload_screenshot(path: paths[index], wait_for_processing: true)
  end

  desired_ids = keepers.map(&:id)
  shot_set.reorder_screenshots(app_screenshot_ids: desired_ids)
  final = Spaceship::ConnectAPI::AppScreenshotSet.get(app_screenshot_set_id: shot_set.id).app_screenshots
  final_checksums = final.map { |item| item.source_file_checksum&.downcase }
  abort("#{locale}: screenshot sync did not converge") unless final.size == 6 && final.all?(&:complete?) && final_checksums == checksums

  puts("#{locale}: 6 ordered screenshots ready in the 6.5-inch slot")
end
