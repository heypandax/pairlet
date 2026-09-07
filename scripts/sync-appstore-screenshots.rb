#!/usr/bin/env ruby
# Make the visible screenshot sets exact and idempotent. fastlane can briefly leave duplicate
# screenshot records while replacing an existing set; this keeps one copy of each desired checksum,
# uploads anything missing, removes every stale record, and restores the filename order.
#
# TWO display types now (issue #334): the 6.5-inch iPhone slot from `fastlane/screenshots/<locale>/`,
# and the 12.9-inch iPad slot from `fastlane/screenshots/<locale>/ipadPro129/`. The iPad files sit in
# a subfolder because `fastlane deliver` globs a language folder NON-recursively
# (Deliver::Loader::LanguageFolder#file_paths) and only descends into `appleTV`/`iMessage` — so
# deliver never sees them, and this script is their only uploader. That is deliberate: 2048x2732 is
# the size of BOTH 12.9-inch slots, and deliver would otherwise have to guess between
# APP_IPAD_PRO_129 and APP_IPAD_PRO_3GEN_129 from the filename alone.
#
# This script owns both screenshot families. Metadata-only deliver must skip screenshots so a
# retry preserves already-correct sets instead of deleting and uploading them again.

require "spaceship"
require_relative "appstore_screenshot_state"

$stdout.sync = true

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

# Converge ONE display type of ONE localization onto exactly `paths`, in that order.
def converge_set(localization, display_type, label, paths)
  locale = localization.locale
  checksums = AppStoreScreenshotState.checksums(paths)

  shot_set = localization.get_app_screenshot_sets.find { |item| item.screenshot_display_type == display_type }
  shot_set ||= localization.create_app_screenshot_set(attributes: { screenshotDisplayType: display_type })
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
    puts("#{locale}: uploading missing #{File.basename(paths[index])} to #{display_type}")
    keepers[index] = shot_set.upload_screenshot(path: paths[index], wait_for_processing: true)
  end

  desired_ids = keepers.map(&:id)
  shot_set.reorder_screenshots(app_screenshot_ids: desired_ids)
  AppStoreScreenshotState.wait_for_set(label: "#{locale} #{label}", checksums: checksums, ids: desired_ids) do
    Spaceship::ConnectAPI::AppScreenshotSet.get(app_screenshot_set_id: shot_set.id).app_screenshots
  end

  puts("#{locale}: 6 ordered screenshots ready in the #{label} slot")
end

localizations = version.get_app_store_version_localizations

%w[en-US zh-Hans].each do |locale|
  localization = localizations.find { |item| item.locale == locale } or abort("missing ASC locale #{locale}")
  AppStoreScreenshotState::SETS.each do |set|
    paths = Dir["fastlane/screenshots/#{locale}/#{set[:glob]}"].sort
    converge_set(localization, set[:type], set[:label], paths)
  end
end
