#!/usr/bin/env ruby
# Read the live questionnaire before submitting; never change a declaration implicitly.
require "spaceship"
require "net/http"
require "json"

token = Spaceship::ConnectAPI::Token.create(
  key_id: ENV.fetch("ASC_KEY_ID"), issuer_id: ENV.fetch("ASC_ISSUER_ID"),
  filepath: File.join(ENV.fetch("RUNNER_TEMP"), "AuthKey.p8"),
)
Spaceship::ConnectAPI.token = token
app = Spaceship::ConnectAPI::App.find("com.panda.ccpocket") or abort("ASC app not found")
info = app.fetch_edit_app_info || app.fetch_live_app_info or abort("No current app info available")
uri = URI("https://api.appstoreconnect.apple.com/v1/appInfos/#{info.id}/ageRatingDeclaration")
request = Net::HTTP::Get.new(uri)
request["Authorization"] = "Bearer #{token.text}"
response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 30) do |http|
  http.request(request)
end
abort("Age rating read failed: HTTP #{response.code}") unless response.is_a?(Net::HTTPSuccess)
attributes = JSON.parse(response.body).fetch("data").fetch("attributes")
abort("Messaging and Chat must be enabled before review") unless attributes["messagingAndChat"] == true
puts("ASC age rating: Messaging and Chat = Yes")
