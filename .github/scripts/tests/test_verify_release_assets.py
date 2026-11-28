import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_release_assets import validate_release_assets


class ReleaseAssetValidationTests(unittest.TestCase):
    def valid_release(self):
        return {
            "tag_name": "v2.2.0",
            "assets": [
                {"name": "LastHavenPlus-2.2.0-stable-arm64-v8a.apk", "size": 100},
                {"name": "lasthavenplus-arm64.apk", "size": 100},
                {"name": "lasthavenplus-update.json", "size": 200},
            ],
        }

    def test_accepts_versioned_release_assets_and_compatibility_alias(self):
        validate_release_assets(self.valid_release(), "v2.2.0")

    def test_rejects_wrong_tag(self):
        with self.assertRaisesRegex(ValueError, "release tag"):
            validate_release_assets(self.valid_release(), "v2.3.0")

    def test_rejects_missing_apk(self):
        release = self.valid_release()
        release["assets"] = [release["assets"][2]]
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_missing_compatibility_alias(self):
        release = self.valid_release()
        release["assets"].pop(1)
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_multiple_apks(self):
        release = self.valid_release()
        release["assets"].append({"name": "unexpected.apk", "size": 100})
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_noncanonical_apk(self):
        release = self.valid_release()
        release["assets"][0]["name"] = "LastHavenPlus-2.1.0-stable-arm64-v8a.apk"
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_unversioned_or_mismatched_release_tag(self):
        release = self.valid_release()
        release["assets"][0]["name"] = "lasthavenplus-unversioned.apk"
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")
        with self.assertRaisesRegex(ValueError, "vMAJOR.MINOR.PATCH"):
            validate_release_assets(release, "latest")

    def test_rejects_missing_or_duplicate_manifest(self):
        release = self.valid_release()
        release["assets"] = release["assets"][:2]
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_release_assets(release, "v2.2.0")
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenplus-update.json", "size": 200})
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_legacy_assets(self):
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenlauncher-arm64.apk", "size": 100})
        with self.assertRaisesRegex(ValueError, "exactly one versioned APK"):
            validate_release_assets(release, "v2.2.0")
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenlauncher-update.json", "size": 200})
        with self.assertRaisesRegex(ValueError, "obsolete"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_compatibility_alias_with_different_size(self):
        release = self.valid_release()
        release["assets"][1]["size"] = 99
        with self.assertRaisesRegex(ValueError, "alias size must match"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_malformed_asset_entries(self):
        release = self.valid_release()
        release["assets"].append("not-an-asset")
        with self.assertRaisesRegex(ValueError, "string name"):
            validate_release_assets(release, "v2.2.0")


if __name__ == "__main__":
    unittest.main()
