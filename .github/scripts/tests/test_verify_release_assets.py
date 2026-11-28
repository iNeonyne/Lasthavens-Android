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
                {"name": "lasthavenplus-arm64.apk"},
                {"name": "lasthavenplus-update.json"},
            ],
        }

    def test_accepts_canonical_release_assets(self):
        validate_release_assets(self.valid_release(), "v2.2.0")

    def test_rejects_wrong_tag(self):
        with self.assertRaisesRegex(ValueError, "release tag"):
            validate_release_assets(self.valid_release(), "v2.3.0")

    def test_rejects_missing_apk(self):
        release = self.valid_release()
        release["assets"] = [release["assets"][1]]
        with self.assertRaisesRegex(ValueError, "exactly one APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_multiple_apks(self):
        release = self.valid_release()
        release["assets"].append({"name": "unexpected.apk"})
        with self.assertRaisesRegex(ValueError, "exactly one APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_noncanonical_apk(self):
        release = self.valid_release()
        release["assets"][0]["name"] = "other-arm64.apk"
        with self.assertRaisesRegex(ValueError, "exactly one APK"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_missing_or_duplicate_manifest(self):
        release = self.valid_release()
        release["assets"] = [release["assets"][0]]
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_release_assets(release, "v2.2.0")
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenplus-update.json"})
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_legacy_assets(self):
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenlauncher-arm64.apk"})
        with self.assertRaisesRegex(ValueError, "exactly one APK"):
            validate_release_assets(release, "v2.2.0")
        release = self.valid_release()
        release["assets"].append({"name": "lasthavenlauncher-update.json"})
        with self.assertRaisesRegex(ValueError, "legacy"):
            validate_release_assets(release, "v2.2.0")

    def test_rejects_malformed_asset_entries(self):
        release = self.valid_release()
        release["assets"].append("not-an-asset")
        with self.assertRaisesRegex(ValueError, "string name"):
            validate_release_assets(release, "v2.2.0")


if __name__ == "__main__":
    unittest.main()
