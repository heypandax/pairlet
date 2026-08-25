#!/usr/bin/env ruby
# Hard gate after upload: confirm the editable ASC version contains the expected media in the same
# 6.5-inch slot shown on the version page. A CI success without these counts is not a delivery.

require "spaceship"

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

shot_type = Spaceship::ConnectAPI::AppScreenshotSet::DisplayType::APP_IPHONE_65
preview_type = Spaceship::ConnectAPI::AppPreviewSet::PreviewType::IPHONE_65
expected_locales = %w[en-US zh-Hans]
localizations = version.get_app_store_version_localizations

expected_locales.each do |locale|
  localization = localizations.find { |item| item.locale == locale } or abort("missing ASC locale #{locale}")

  shot_set = localization.get_app_screenshot_sets.find { |item| item.screenshot_display_type == shot_type }
  shots = shot_set&.app_screenshots || []
  bad_shots = shots.reject(&:complete?)
  abort("#{localization.locale}: expected 6 complete #{shot_type} screenshots, got #{shots.size}") unless shots.size == 6 && bad_shots.empty?

  preview_set = localization.get_app_preview_sets.find { |item| item.preview_type == preview_type }
  previews = preview_set&.app_previews || []
  good_previews = previews.select { |item| item.video_url && item.complete? }
  abort("#{localization.locale}: expected 1 processed #{preview_type} preview, got #{previews.size}") unless previews.size == 1 && good_previews.size == 1

  puts("#{localization.locale}: #{shots.size} screenshots + #{previews.size} preview in the 6.5-inch slot")
end
