import unittest

from scripts.testflight_group_access import BuildGroupAccessError, ensure_build_group_access


def render_errors(body):
    return body.get("detail", "unknown")


class TestFlightGroupAccessTest(unittest.TestCase):
    def test_all_build_group_needs_no_relationship_mutation(self):
        calls = []

        def call(method, path, payload=None):
            calls.append((method, path, payload))
            return 500, {}

        result = ensure_build_group_access(
            call,
            {"id": "group", "attributes": {"hasAccessToAllBuilds": True}},
            "build",
            render_errors,
        )

        self.assertEqual("all-build group already grants access", result)
        self.assertEqual([], calls)

    def test_existing_relationship_is_idempotent(self):
        def call(method, path, payload=None):
            self.assertEqual("GET", method)
            return 200, {"data": [{"type": "builds", "id": "build"}]}

        result = ensure_build_group_access(
            call, {"id": "group", "attributes": {}}, "build", render_errors
        )

        self.assertEqual("already attached", result)

    def test_group_relationship_attaches_normally(self):
        responses = iter([(200, {"data": []}), (204, {})])

        def call(method, path, payload=None):
            return next(responses)

        result = ensure_build_group_access(
            call, {"id": "group", "attributes": {}}, "build", render_errors
        )

        self.assertEqual("attached", result)

    def test_observed_404_uses_documented_inverse_relationship(self):
        calls = []
        responses = iter(
            [
                (200, {"data": []}),
                (404, {"detail": "build not found yet"}),
                (204, {}),
            ]
        )

        def call(method, path, payload=None):
            calls.append((method, path, payload))
            return next(responses)

        result = ensure_build_group_access(
            call, {"id": "group", "attributes": {}}, "build", render_errors
        )

        self.assertEqual("attached through build relationship", result)
        self.assertEqual("/v1/builds/build/relationships/betaGroups", calls[-1][1])

    def test_transient_404_is_retried_with_bounded_wait(self):
        sleeps = []
        responses = iter(
            [
                (200, {"data": []}),
                (404, {"detail": "not propagated"}),
                (404, {"detail": "not propagated"}),
                (200, {"data": []}),
                (204, {}),
            ]
        )

        def call(method, path, payload=None):
            return next(responses)

        result = ensure_build_group_access(
            call,
            {"id": "group", "attributes": {}},
            "build",
            render_errors,
            attempts=2,
            retry_delay_seconds=7,
            sleep=sleeps.append,
        )

        self.assertEqual("attached", result)
        self.assertEqual([7], sleeps)

    def test_non_transient_failure_is_not_hidden(self):
        responses = iter([(200, {"data": []}), (403, {"detail": "forbidden"})])

        def call(method, path, payload=None):
            return next(responses)

        with self.assertRaisesRegex(BuildGroupAccessError, "403: forbidden"):
            ensure_build_group_access(
                call, {"id": "group", "attributes": {}}, "build", render_errors
            )


if __name__ == "__main__":
    unittest.main()
