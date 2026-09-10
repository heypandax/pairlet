require "minitest/autorun"
require_relative "check-appstore-version"

class AppStoreVersionGuardTest < Minitest::Test
  def check(state, target: "2.0.0", editable: nil, extra: [])
    AppStoreVersionGuard.check!(
      target: target, editable: editable,
      versions: [{ number: "1.9.8", state: state }] + extra,
    )
  end

  def test_inflight_versions_cannot_be_renumbered_or_bypassed
    %w[IN_REVIEW WAITING_FOR_REVIEW PENDING_DEVELOPER_RELEASE PENDING_APPLE_RELEASE PREPARE_FOR_SUBMISSION].each do |state|
      assert_raises(RuntimeError) { check(state) }
      assert_raises(RuntimeError) { check(state, editable: "1.9.8") }
    end
  end

  def test_create_only_after_release_and_with_increasing_version
    %w[READY_FOR_DISTRIBUTION READY_FOR_SALE].each do |state|
      assert_equal :create, check(state)
      assert_raises(RuntimeError) { check(state, target: "1.9.7") }
      assert_raises(RuntimeError) { check(state, target: "1.9.8") }
    end
  end

  def test_only_the_matching_editable_latest_draft_can_be_updated
    draft = [{ number: "2.0.0", state: "PREPARE_FOR_SUBMISSION" }]
    assert_equal :update, check("READY_FOR_DISTRIBUTION", editable: "2.0.0", extra: draft)
    assert_raises(RuntimeError) { check("READY_FOR_DISTRIBUTION", editable: "1.9.8", extra: draft) }
    assert_raises(RuntimeError) do
      check("READY_FOR_DISTRIBUTION", editable: "2.0.0", extra: draft + [{ number: "2.1.0", state: "IN_REVIEW" }])
    end
  end
end
