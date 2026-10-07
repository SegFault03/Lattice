import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("archive", Path(__file__).resolve().parents[1] / "check-release-archive.py")
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


class ArchiveTests(unittest.TestCase):
    def package(self, path, extra=None, major=65):
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, "w") as plugin:
            plugin.writestr("META-INF/plugin.xml", '<idea-plugin><version>1.0.1</version><idea-version since-build="251"/></idea-plugin>')
            for name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/mysql-connector-j-9.0.0-LICENSE.txt", "licenses/hsqldb-LICENSE.txt", "licenses/protobuf-LICENSE.txt"):
                plugin.writestr(name, "notice")
            plugin.writestr("sample.class", b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big"))
        with zipfile.ZipFile(path, "w") as outer:
            outer.writestr("Lattice/lib/Lattice.jar", memory.getvalue())
            outer.writestr("Lattice/lib/mysql-connector-j-9.0.0.jar", "driver")
            outer.writestr("Lattice/lib/hsqldb-2.7.3.jar", "driver")
            outer.writestr("Lattice/lib/protobuf-java-4.28.2.jar", "runtime")
            if extra:
                outer.writestr(extra, "unwanted")

    def test_version_and_java_baseline(self):
        with tempfile.TemporaryDirectory(prefix="lattice-archive-test-") as directory:
            path = Path(directory) / "plugin.zip"
            self.package(path)
            archive.check(path, "1.0.1")
            with self.assertRaises(ValueError):
                archive.check(path, "1.0.2")
            self.package(path, major=69)
            with self.assertRaises(ValueError):
                archive.check(path, "1.0.1")

    def test_rejects_unsafe_paths_and_test_binaries(self):
        with tempfile.TemporaryDirectory(prefix="lattice-archive-test-") as directory:
            path = Path(directory) / "plugin.zip"
            for extra in ("../secret", "Lattice\\lib\\unsafe.jar", "Lattice/lib/mysqld.exe"):
                self.package(path, extra=extra)
                with self.assertRaises(ValueError):
                    archive.check(path, "1.0.1")
