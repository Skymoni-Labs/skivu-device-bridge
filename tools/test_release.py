"""Release boundary tests: tag consistency and distributable contents."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import release


class ReleaseTest(unittest.TestCase):
    def test_tag_must_match_version(self):
        with self.assertRaisesRegex(ValueError, "must match"):
            release.check("v999.0.0")

    def test_component_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "package.json").write_text(json.dumps({"version": "1.0.0"}))
            (root / "app/build.gradle.kts").write_text('versionName = "0.9.0"')
            with patch.object(release, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "Version mismatch"):
                    release.check()

    def test_bundle_checksums_licenses_and_no_unlisted_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / "input.apk"
            apk.write_bytes(b"test fixture, not an installable APK")
            (root / "private.jks").write_bytes(b"never ship")
            output = release.bundle(apk, root / "public")
            version = release.check()
            self.assertEqual({f"PhoneUse-{version}.apk", f"phone-use-{version}.zip",
                              f"phoneuse-mcp-{version}.zip", "LICENSE", "SHA256SUMS.txt"},
                             {path.name for path in output.iterdir()})
            for line in (output / "SHA256SUMS.txt").read_text().splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())
            for name in ("phone-use", "phoneuse-mcp"):
                with ZipFile(output / f"{name}-{version}.zip") as archive:
                    self.assertEqual((release.ROOT / "LICENSE").read_bytes(),
                                     archive.read(f"{name}/LICENSE"))
            (output / "private.txt").write_text("keep")
            with self.assertRaisesRegex(ValueError, "already exists"):
                release.bundle(apk, output)
            self.assertEqual("keep", (output / "private.txt").read_text())


if __name__ == "__main__":
    unittest.main()
