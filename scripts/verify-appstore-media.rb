#!/usr/bin/env ruby
# Read-only hard gate: both locales, both screenshot families, exact content/order, and previews.

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
version = app.get_edit_app_store_version(platform: Spaceship::ConnectAPI::Platform.map("ios")) or abort("no editable iOS version")
abort("editable version is #{version.version_string}, expected #{version_string}") unless version.version_string == version_string

puts("ASC iOS #{version.version_string}: #{version.app_store_state}")
preview_type = Spaceship::ConnectAPI::AppPreviewSet::PreviewType::IPHONE_65
expected_locales = %w[en-US zh-Hans]
localizations = version.get_app_store_version_localizations

expected_locales.each do |locale|
  localization = localizations.find { |item| item.locale == locale } or abort("missing ASC locale #{locale}")

  shot_sets = localization.get_app_screenshot_sets
  AppStoreScreenshotState::SETS.each do |set|
    paths = Dir["fastlane/screenshots/#{locale}/#{set[:glob]}"].sort
    checksums = AppStoreScreenshotState.checksums(paths)
    shot_set = shot_sets.find { |item| item.screenshot_display_type == set[:type] }
    abort("#{locale}: missing #{set[:type]} screenshot set") unless shot_set
    shots = AppStoreScreenshotState.wait_for_set(label: "#{locale} #{set[:label]}", checksums: checksums) do
      Spaceship::ConnectAPI::AppScreenshotSet.get(app_screenshot_set_id: shot_set.id).app_screenshots
    end
    puts("#{locale}: #{shots.size} complete #{set[:type]} screenshots; ordered source checksums match")
  end

  preview_set = localization.get_app_preview_sets.find { |item| item.preview_type == preview_type }
  previews = preview_set&.app_previews || []
  good_previews = previews.select { |item| item.video_url && item.complete? }
  abort("#{localization.locale}: expected 1 processed #{preview_type} preview, got #{previews.size}") unless previews.size == 1 && good_previews.size == 1

  puts("#{locale}: #{previews.size} processed iPhone 6.5-inch preview")
end
