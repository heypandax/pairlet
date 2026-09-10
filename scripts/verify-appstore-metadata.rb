#!/usr/bin/env ruby
# Read back the text actually staged for deliver, without printing private review information.
require "spaceship"

Spaceship::ConnectAPI.token = Spaceship::ConnectAPI::Token.create(
  key_id: ENV.fetch("ASC_KEY_ID"), issuer_id: ENV.fetch("ASC_ISSUER_ID"),
  filepath: File.join(ENV.fetch("RUNNER_TEMP"), "AuthKey.p8"),
)
app = Spaceship::ConnectAPI::App.find("com.panda.ccpocket") or abort("ASC app not found")
version = app.get_edit_app_store_version(platform: "IOS") or abort("no editable iOS version")
abort("unexpected editable version #{version.version_string}") unless version.version_string == ENV.fetch("VERSION")
abort("expected manual release") unless version.release_type == "MANUAL"
info = app.fetch_edit_app_info or abort("no editable app info")
app_locales = info.get_app_info_localizations
version_locales = version.get_app_store_version_localizations
metadata = ENV.fetch("METADATA_PATH")
fields = {
  "name" => [:app, :name], "subtitle" => [:app, :subtitle], "privacy_url" => [:app, :privacy_policy_url],
  "description" => [:version, :description], "keywords" => [:version, :keywords],
  "promotional_text" => [:version, :promotional_text], "marketing_url" => [:version, :marketing_url],
  "support_url" => [:version, :support_url], "release_notes" => [:version, :whats_new],
}

%w[en-US zh-Hans].each do |locale|
  records = { app: app_locales.find { |item| item.locale == locale }, version: version_locales.find { |item| item.locale == locale } }
  abort("missing #{locale} localization") if records.values.any?(&:nil?)
  count = 0
  fields.each do |file, (kind, attribute)|
    path = File.join(metadata, locale, "#{file}.txt")
    next unless File.file?(path)
    expected = File.read(path).strip.gsub("\r\n", "\n")
    actual = records.fetch(kind).public_send(attribute).to_s.strip.gsub("\r\n", "\n")
    abort("#{locale}/#{file}: ASC text differs from staged source") unless actual == expected
    count += 1
  end
  puts("#{locale}: #{count} text fields match staged source")
end

notes = File.join(metadata, "review_information", "notes.txt")
if File.file?(notes)
  actual = version.fetch_app_store_review_detail&.notes.to_s.strip.gsub("\r\n", "\n")
  abort("ASC review notes differ from staged source") unless actual == File.read(notes).strip.gsub("\r\n", "\n")
  puts("Review notes match staged source")
end
puts("Version #{version.version_string}: manual release; text verified")
