require "digest"
require "json"

# Shared by the uploader and the independent read-only verification step.
module AppStoreScreenshotState
  SETS = [
    { type: "APP_IPHONE_65", label: "iPhone 6.5-inch", glob: "*.png" },
    { type: "APP_IPAD_PRO_3GEN_129", label: "iPad 12.9-inch", glob: "ipadPro129/*.png" },
  ].freeze

  def self.checksums(paths)
    raise "expected 6 local screenshots, got #{paths.size}" unless paths.size == 6

    paths.map { |path| Digest::MD5.file(path).hexdigest }
  end

  # Individual uploads can be COMPLETE before the collection read reflects their
  # checksums/order. Retry reads only; never delete or re-upload during this wait.
  def self.wait_for_set(label:, checksums:, ids: nil, attempts: 13, interval: 5, output: $stdout)
    previous = nil
    attempts.times do |attempt|
      shots = yield
      actual_checksums = shots.map { |shot| shot.source_file_checksum&.downcase }
      if shots.size == checksums.size && shots.all?(&:complete?) && actual_checksums == checksums &&
         (ids.nil? || shots.map(&:id) == ids)
        return shots
      end

      snapshot = shots.map do |shot|
        { id: shot.id, name: shot.file_name, state: shot.asset_delivery_state,
          checksum: shot.source_file_checksum }
      end.to_json
      output.puts("#{label}: waiting for screenshot collection: #{snapshot}") if snapshot != previous
      previous = snapshot
      failed = shots.select(&:error?)
      unless failed.empty?
        raise "#{label}: screenshot processing failed: #{failed.map { |shot| [shot.file_name, shot.error_messages] }.inspect}"
      end
      sleep(interval) if attempt + 1 < attempts && interval.positive?
    end
    raise "#{label}: screenshot sync did not converge after #{attempts} reads; expected checksums=#{checksums.inspect}; last=#{previous}"
  end
end
