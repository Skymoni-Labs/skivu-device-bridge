"""Guard against writing copies into an uninitialized Skill submodule."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import export_integrations
import sync_skill


class MissingSkillTest(unittest.TestCase):
    def test_missing_submodule_fails_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(sync_skill, "ROOT", root):
                for check in (False, True):
                    with self.subTest(check=check):
                        with self.assertRaisesRegex(SystemExit, "git submodule update --init"):
                            sync_skill.sync(check=check)
                with self.assertRaisesRegex(SystemExit, "git submodule update --init"):
                    export_integrations.export(root / "output")
            self.assertEqual([], list(root.iterdir()))
