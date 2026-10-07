import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import os
import stat
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("verifier", Path(__file__).resolve().parents[1] / "verify-plugin.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class SdkTests(unittest.TestCase):
    def test_verdict_accepts_compatible_summary_but_not_other_statuses(self):
        self.assertTrue(verifier.compatible_verdict("Compatible"))
        self.assertTrue(verifier.compatible_verdict("Compatible. 2 usages of deprecated API."))
        self.assertFalse(verifier.compatible_verdict("Not compatible"))
        self.assertFalse(verifier.compatible_verdict("Compatibility problems found"))

    def sdk_archive(self, cache, unsafe=False):
        archive = cache / "archives/ides/idea-2025.1-linux.tar.gz"
        archive.parent.mkdir(parents=True)
        with tarfile.open(archive, "w:gz") as bundle:
            directory = tarfile.TarInfo("idea/lib")
            directory.type = tarfile.DIRTYPE
            directory.mode = 0o755
            bundle.addfile(directory)
            for name in ("idea/product-info.json", "idea/lib/platform.jar", "idea/jbr/bin/java", "../outside" if unsafe else "idea/NOTICE"):
                item = tarfile.TarInfo(name)
                item.size = 2
                bundle.addfile(item, io.BytesIO(b"{}"))
            link = tarfile.TarInfo("idea/jbr/lib/native-link")
            link.type = tarfile.SYMTYPE
            link.linkname = "/outside"
            bundle.addfile(link)
        metadata = {"IIC": [{"version": "2025.1", "downloads": {"linux": {"link": "https://example.invalid/sdk", "checksumLink": "https://example.invalid/checksum"}}}]}
        return [io.BytesIO(json.dumps(metadata).encode()), io.BytesIO(hashlib.sha256(archive.read_bytes()).hexdigest().encode())]

    def test_sdk_extracts_without_native_links_and_reuses_completed_cache(self):
        with tempfile.TemporaryDirectory(prefix="lattice-sdk-test-") as directory:
            cache = Path(directory)
            with patch.object(verifier.urllib.request, "urlopen", side_effect=self.sdk_archive(cache)):
                sdk = verifier.ide_home(cache, "2025.1")
            self.assertTrue((sdk / "lib/platform.jar").is_file())
            self.assertTrue((sdk / "jbr/bin/java").is_file())
            self.assertFalse((sdk / "jbr/lib/native-link").exists())
            if os.name != "nt":
                self.assertTrue((sdk / "lib").stat().st_mode & stat.S_IXUSR)
            with patch.object(verifier.urllib.request, "urlopen") as network:
                self.assertEqual(sdk, verifier.ide_home(cache, "2025.1"))
                network.assert_not_called()

    def test_sdk_rejects_traversal_and_does_not_mark_partial_complete(self):
        with tempfile.TemporaryDirectory(prefix="lattice-sdk-test-") as directory:
            cache = Path(directory)
            with patch.object(verifier.urllib.request, "urlopen", side_effect=self.sdk_archive(cache, unsafe=True)):
                with self.assertRaises(tarfile.FilterError):
                    verifier.ide_home(cache, "2025.1")
            self.assertFalse(list(cache.rglob(".complete")))
            self.assertFalse((cache / "ides/outside").exists())

    def test_verifier_reports_and_scratch_are_separate_and_unique(self):
        with tempfile.TemporaryDirectory(prefix="lattice-verifier-test-") as directory:
            root = Path(directory)
            archive = root / "plugin.zip"
            archive.write_bytes(b"placeholder")
            jar = root / "tools/verifier/verifier-cli-1.410-all.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"placeholder")
            report_paths = []
            def launch(command, check):
                report = Path(command[command.index("-verification-reports-dir") + 1])
                scratch = Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("-Dplugin.verifier.home.dir=")))
                self.assertFalse(scratch.is_relative_to(report))
                self.assertFalse(report.is_relative_to(scratch))
                report_paths.append(report)
                report.mkdir(parents=True)
                (report / "verification-verdict.txt").write_text("Compatible", encoding="utf-8")
            argv = ["verify-plugin.py", str(archive), "--ide-home", str(root), "--cache", str(root), "--reports", str(root / "reports")]
            with patch.object(sys, "argv", argv), patch.object(verifier, "java_command", return_value="java"), \
                 patch.object(verifier, "digest", return_value=verifier.VERIFIER_SHA256), \
                 patch.object(verifier.subprocess, "run", side_effect=launch):
                verifier.main()
                verifier.main()
            self.assertNotEqual(report_paths[0], report_paths[1])


if __name__ == "__main__":
    unittest.main()
