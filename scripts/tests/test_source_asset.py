"""Source assets must be verified before their filename is handed to release jobs."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("source_asset", SCRIPTS / "prepare-source-asset.py")
source_asset = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source_asset)


class SourceAssetTests(unittest.TestCase):
    def test_release_receives_the_filename_of_the_verified_asset(self):
        with tempfile.TemporaryDirectory(prefix="lattice-source-test-") as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            content = b"verified corresponding source fixture"
            (cache / source_asset.NAME).write_bytes(content)
            output = root / "assets"
            github_output = root / "outputs"
            with patch.object(source_asset, "SHA256", hashlib.sha256(content).hexdigest()), patch.object(sys, "argv", [
                "prepare-source-asset.py", "--cache", str(cache), "--output", str(output),
                "--github-output", str(github_output),
            ]):
                source_asset.main()
            name = github_output.read_text().strip().removeprefix("filename=")
            self.assertEqual(content, (output / name).read_bytes())

    def test_corrupt_cached_source_cannot_be_published(self):
        with tempfile.TemporaryDirectory(prefix="lattice-source-test-") as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            (cache / source_asset.NAME).write_bytes(b"damaged source")
            github_output = root / "outputs"
            with patch.object(sys, "argv", ["prepare-source-asset.py", "--cache", str(cache),
                                           "--output", str(root / "assets"), "--github-output", str(github_output)]):
                with self.assertRaisesRegex(ValueError, "checksum/size"):
                    source_asset.main()
            self.assertFalse(github_output.exists())
            self.assertFalse((root / "assets").exists())


if __name__ == "__main__":
    unittest.main()
