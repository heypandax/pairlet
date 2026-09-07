require "minitest/autorun"
require "stringio"
require_relative "appstore_screenshot_state"

class AppStoreScreenshotStateTest < Minitest::Test
  Shot = Struct.new(:id, :file_name, :source_file_checksum, :asset_delivery_state) do
    def complete?
      asset_delivery_state["state"] == "COMPLETE"
    end
    def error?
      asset_delivery_state["state"] == "FAILED"
    end
    def error_messages
      ["processing rejected"]
    end
  end

  def shot(id, checksum, state = "COMPLETE")
    Shot.new(id, "#{id}.png", checksum, { "state" => state })
  end

  def wait_for(snapshots, **options)
    AppStoreScreenshotState.wait_for_set(
      label: "en-US iPad", checksums: %w[aa bb], ids: %w[1 2],
      attempts: snapshots.size, interval: 0, output: StringIO.new, **options
    ) { snapshots.shift }
  end

  def test_waits_for_stale_order_and_processing_reads
    final = [shot("1", "AA"), shot("2", "bb")]
    assert_equal final, wait_for([
      [shot("2", "bb"), shot("1", "aa")],
      [shot("1", nil, "PROCESSING"), shot("2", "bb")],
      final,
    ])
  end

  def test_missing_extra_wrong_content_and_order_never_pass_on_counts_alone
    [
      [shot("1", "aa")],
      [shot("1", "aa"), shot("2", "bb"), shot("3", "cc")],
      [shot("1", "aa"), shot("2", "cc")],
      [shot("2", "bb"), shot("1", "aa")],
      [shot("3", "aa"), shot("2", "bb")],
    ].each do |snapshot|
      error = assert_raises(RuntimeError) { wait_for([snapshot]) }
      assert_includes error.message, "did not converge"
    end
  end

  def test_processing_failure_is_not_retried_as_success
    error = assert_raises(RuntimeError) do
      wait_for([[shot("1", "aa", "FAILED"), shot("2", "bb")]])
    end
    assert_includes error.message, "processing failed"
  end

  def test_both_device_families_and_complete_local_manifest_are_required
    assert_equal %w[APP_IPHONE_65 APP_IPAD_PRO_3GEN_129], AppStoreScreenshotState::SETS.map { |set| set[:type] }
    %w[en-US zh-Hans].each do |locale|
      AppStoreScreenshotState::SETS.each do |set|
        paths = Dir[File.join(__dir__, "..", "fastlane", "screenshots", locale, set[:glob])].sort
        assert_equal 6, AppStoreScreenshotState.checksums(paths).size
      end
    end
    assert_raises(RuntimeError) { AppStoreScreenshotState.checksums([]) }
  end
end
