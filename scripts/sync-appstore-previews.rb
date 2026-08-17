#!/usr/bin/env ruby
# Upload one processed App Preview per locale without removing the existing video until the new
# file has finished processing. Requires fastlane (Spaceship) and the ASC env used by CI.

require "digest"
require "spaceship"
require "timeout"

version_string = ENV.fetch("VERSION")
runner_temp = ENV.fetch("RUNNER_TEMP")
token = Spaceship::ConnectAPI::Token.create(
  key_id: ENV.fetch("ASC_KEY_ID"),
  issuer_id: ENV.fetch("ASC_ISSUER_ID"),
  filepath: File.join(runner_temp, "AuthKey.p8"),
)
Spaceship::ConnectAPI.token = token

app = Spaceship::ConnectAPI::App.find("com.panda.ccpocket") or abort("ASC app not found")
platform = Spaceship::ConnectAPI::Platform.map("ios")
version = app.get_edit_app_store_version(platform: platform) or abort("no editable iOS version")
abort("editable version is #{version.version_string}, expected #{version_string}") unless version.version_string == version_string

preview_type = Spaceship::ConnectAPI::AppPreviewSet::PreviewType::IPHONE_65
paths = {
  "en-US" => "fastlane/previews/en-US/app-preview.mov",
  "zh-Hans" => "fastlane/previews/zh-Hans/app-preview.mov",
}
localizations = version.get_app_store_version_localizations

paths.each do |locale, path|
  abort("missing preview: #{path}") unless File.file?(path)
  localization = localizations.find { |item| item.locale == locale } or abort("missing ASC locale #{locale}")
  preview_set = localization.get_app_preview_sets.find { |item| item.preview_type == preview_type }
  preview_set ||= localization.create_app_preview_set(attributes: { previewType: preview_type })

  checksum = Digest::MD5.file(path).hexdigest
  current = Spaceship::ConnectAPI::AppPreviewSet.get(app_preview_set_id: preview_set.id)
  keep = current.app_previews.find { |item| item.source_file_checksum == checksum && item.video_url }

  unless keep
    puts("#{locale}: uploading #{File.basename(path)} to #{preview_type}")
    keep = Timeout.timeout(45 * 60) do
      preview_set.upload_preview(
        path: path,
        wait_for_processing: true,
        position: 0,
        frame_time_code: "00:00:05:00",
      )
    end
  end

  # The replacement is now processed. Only now remove stale previews, then pin the new one first.
  refreshed = Spaceship::ConnectAPI::AppPreviewSet.get(app_preview_set_id: preview_set.id)
  refreshed.app_previews.reject { |item| item.id == keep.id }.each(&:delete!)
  preview_set.reorder_previews(app_preview_ids: [keep.id])
  puts("#{locale}: preview ready id=#{keep.id} checksum=#{checksum}")
end
