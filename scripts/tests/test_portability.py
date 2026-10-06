import argparse
from pathlib import Path
import sys
import subprocess
import tempfile
import os
import unittest
from unittest.mock import patch, MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common
import test as runner


class PortabilityTests(unittest.TestCase):
    def test_user_cache_platforms_and_override(self):
        with patch.dict(common.os.environ, {"LATTICE_DEV_CACHE": "relative-cache"}, clear=True):
            self.assertEqual(Path("relative-cache").resolve(), common.cache_directory())
        with patch.dict(common.os.environ, {}, clear=True), patch.object(common.Path, "home", return_value=Path("user-home")):
            for platform, suffix in (("win32", "AppData/Local/lattice-development"),
                                     ("darwin", "Library/Caches/lattice-development"),
                                     ("linux", ".cache/lattice-development")):
                with patch.object(common.sys, "platform", platform):
                    self.assertEqual(Path("user-home") / suffix, common.cache_directory())

    def test_gradle_tasks_and_paths(self):
        args = argparse.Namespace(live=True, build=True, version="1.2.3", ide_home=Path("sdk with spaces"), notes_file=None)
        command = runner.command(args)
        self.assertIn("integrationTest", command)
        self.assertIn("fallbackDriverTest", command)
        self.assertIn("buildPlugin", command)
        self.assertIn("-Plattice.ide.home=" + str(args.ide_home.resolve()), command)
        args.version = "1.0.0&echo injected"
        with self.assertRaises(ValueError):
            runner.command(args)

    def test_fixture_cleanup_after_readiness_failure(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(runner, "require_free_port"), patch.object(runner, "java_command", return_value="java"), \
             patch.object(runner.subprocess, "Popen", return_value=process), \
             patch.object(runner, "wait_port", side_effect=TimeoutError("not ready")), \
             patch.object(runner.Path, "mkdir"), patch.object(runner.Path, "open", return_value=MagicMock()):
            with self.assertRaises(TimeoutError):
                with runner.hsqldb_fixture():
                    self.fail("Unready fixture was used")
        process.terminate.assert_called_once()
        process.wait.assert_called_once()

    def test_existing_port_is_not_taken_over(self):
        with patch.object(runner, "require_free_port", side_effect=OSError("occupied")), \
             patch.object(runner.subprocess, "Popen") as launch:
            with self.assertRaises(OSError):
                with runner.hsqldb_fixture():
                    self.fail("Existing fixture was taken over")
        launch.assert_not_called()

    def test_wrapper_launch_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix="lattice wrapper ") as directory:
            root = Path(directory)
            if os.name == "nt":
                (root / "gradlew.bat").write_text('@echo off\necho %~1\n', encoding="ascii")
            else:
                wrapper = root / "gradlew"
                wrapper.write_text('#!/bin/sh\nprintf "%s\\n" "$1"\n', encoding="ascii")
                wrapper.chmod(0o755)
            with patch.object(common, "ROOT", root), patch.dict(os.environ, {"NoDefaultCurrentDirectoryInExePath": "1"}):
                result = subprocess.check_output(common.gradle_command() + ["argument with spaces"], cwd=root, text=True)
            self.assertEqual("argument with spaces", result.strip())


if __name__ == "__main__":
    unittest.main()
