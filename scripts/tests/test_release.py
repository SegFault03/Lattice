import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_strict_versions(self):
        for value in ("1.0.0", "10.2.34", "0.0.1-alpha", "1.0.0-rc.1", "1.0.0-alpha-beta"):
            self.assertEqual(value, release.validate_version(value))
        for value in ("v1.0.0", "01.0.0", "1.0", "1.0.0\n", "1.0.0;echo injected", "1.0.0-", "1.0.0-alpha..1", "1.0.0-01", "1.0.0-alpha.01", "1.0.0+metadata"):
            with self.assertRaises(ValueError):
                release.validate_version(value)

    def test_prerelease_precedence(self):
        versions = ["0.0.1-alpha", "0.0.1-alpha.1", "0.0.1-alpha.beta", "0.0.1-beta", "0.0.1-beta.2", "0.0.1-beta.11", "0.0.1-rc.1", "0.0.1", "0.0.2-alpha"]
        self.assertEqual(versions, sorted(reversed(versions), key=release.version_key))

    def test_patch_notes_choose_exact_release(self):
        changelog = "## [Unreleased]\n- Future fix\n## [1.0.0]\n- Released fix\n## [0.9.0]\n- Old fix\n"
        self.assertEqual("- Released fix", release.patch_notes(changelog, "1.0.0"))
        self.assertEqual("- Future fix", release.patch_notes(changelog, "1.0.1"))

    def test_empty_notes_block_release(self):
        with self.assertRaises(ValueError):
            release.patch_notes("## [Unreleased]\n\n## [0.9.0]\n- Old fix", "1.0.0")

    def test_history_and_tag_validation(self):
        with tempfile.TemporaryDirectory(prefix="lattice-release-test-") as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()
            git("init", "--quiet")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "Release test")
            git("config", "commit.gpgsign", "false")
            (root / "CHANGELOG.md").write_text("## [Unreleased]\n- New fix\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "Initial commit")
            git("tag", "v1.0.0")
            (root / "change.txt").write_text("updated", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "Fix <script> & [label]")
            git("tag", "v1.0.1")
            body, plugin = release.prepare(root, "1.0.1", "v1.0.1", "owner/repo")
            self.assertIn("Fix &lt;script&gt; &amp; \\[label\\]", body)
            self.assertNotIn("Initial commit", body)
            self.assertIn("v1.0.0...v1.0.1", body)
            self.assertIn("- New fix", plugin)
            with self.assertRaises(ValueError):
                release.prepare(root, "1.0.0", "v1.0.0")
            git("tag", "v1.0.2-alpha")
            self.assertEqual("v1.0.2-alpha", release.previous_tag(root, "1.0.2-beta"))
            self.assertEqual("v1.0.1", release.previous_tag(root, "1.0.2-alpha"))


if __name__ == "__main__":
    unittest.main()
