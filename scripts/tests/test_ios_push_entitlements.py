"""Regression coverage for signed apps that build successfully but cannot register with APNs."""

import copy
import importlib.util
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "push_signing", Path(__file__).resolve().parents[1] / "check-ios-push-entitlements.py"
)
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)

BUNDLE = "com.example.push"
TEAM = "EXAMPLETEAM"


class PushSigningTest(unittest.TestCase):
    def setUp(self):
        self.info = {"CFBundleIdentifier": BUNDLE}
        self.signed = {
            "application-identifier": f"{TEAM}.{BUNDLE}",
            "com.apple.developer.team-identifier": TEAM,
            "get-task-allow": False,
            "aps-environment": "production",
        }
        self.profile = {"Entitlements": copy.deepcopy(self.signed)}

    def check(self, environment="production"):
        signing.validate_entitlements(self.info, self.signed, self.profile, environment, BUNDLE, TEAM)

    def test_distribution_app_and_profile_pass(self):
        self.check()

    def test_profile_permission_does_not_compensate_for_missing_signed_entitlement(self):
        # The broken CI archive only signed app/team identifiers and get-task-allow.
        # A profile that permits APNs does not grant the missing claim to the app.
        del self.signed["aps-environment"]
        with self.assertRaisesRegex(ValueError, "app signature: aps-environment"):
            self.check()

    def test_missing_profile_push_capability_fails(self):
        del self.profile["Entitlements"]["aps-environment"]
        with self.assertRaisesRegex(ValueError, "provisioning profile: aps-environment"):
            self.check()

    def test_development_archive_passes_but_cannot_be_uploaded_as_production(self):
        for claims in (self.signed, self.profile["Entitlements"]):
            claims["aps-environment"] = "development"
            claims["get-task-allow"] = True
        self.check("development")
        with self.assertRaisesRegex(ValueError, "aps-environment must be production"):
            self.check()

    def test_wrong_profile_environment_fails_after_resigning(self):
        self.profile["Entitlements"]["aps-environment"] = "development"
        with self.assertRaisesRegex(ValueError, "provisioning profile: aps-environment"):
            self.check()

    def test_wrong_app_or_team_fails(self):
        for claims in (self.signed, self.profile["Entitlements"]):
            for key in ("application-identifier", "com.apple.developer.team-identifier"):
                with self.subTest(key=key):
                    original = claims[key]
                    claims[key] = "unrelated.app.or.team"
                    with self.assertRaisesRegex(ValueError, "does not match"):
                        self.check()
                    claims[key] = original
        self.info["CFBundleIdentifier"] = "com.example.unrelated"
        with self.assertRaisesRegex(ValueError, "bundle identifier"):
            self.check()

    def test_debuggable_production_claims_are_rejected(self):
        for claims in (self.signed, self.profile["Entitlements"]):
            claims["get-task-allow"] = True
            with self.assertRaisesRegex(ValueError, "get-task-allow=false"):
                self.check()
            claims["get-task-allow"] = False

    def test_invalid_signature_stops_inspection_even_with_correct_source_plist(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "Example.app"
            app.mkdir()
            (app / "Info.plist").write_bytes(plistlib.dumps(self.info))
            (app / "Example.entitlements").write_bytes(plistlib.dumps(self.signed))
            with patch.object(signing.subprocess, "run") as run:
                run.side_effect = subprocess.CalledProcessError(1, ["codesign"])
                with self.assertRaises(subprocess.CalledProcessError):
                    signing.inspect_app(app, "production", BUNDLE, TEAM)
                run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
