import argparse
from pathlib import Path
import sys
import subprocess
import tempfile
import os
import socket
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
        args.integration_only = True
        integration_command = runner.command(args)
        self.assertIn("integrationTest", integration_command)
        self.assertIn("fallbackDriverTest", integration_command)
        self.assertNotIn("test", integration_command)
        args.integration_only = False
        args.version = "1.0.0&echo injected"
        with self.assertRaises(ValueError):
            runner.command(args)

    def test_java_home_selects_java_21_for_gradle_and_rejects_other_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            binary = home / "bin" / ("java.exe" if os.name == "nt" else "java")
            binary.parent.mkdir()
            binary.touch()
            release = home / "release"
            release.write_text('JAVA_VERSION="21.0.6"\n', encoding="utf-8")

            environment = runner.gradle_environment(home)
            self.assertEqual(home.resolve(), Path(environment["JAVA_HOME"]))

            release.write_text('JAVA_VERSION="24.0.2"\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Java 21"):
                runner.gradle_environment(home)

    def test_fixture_cleanup_after_readiness_failure(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(runner, "require_free_port"), patch.object(runner, "java_command", return_value="java"), \
             patch.object(runner.subprocess, "Popen", return_value=process), \
             patch('windows.process_job.OwnedProcessJob'), \
             patch.object(runner, "wait_port", side_effect=TimeoutError("not ready")), \
             patch.object(runner.Path, "mkdir"), patch.object(runner.Path, "is_file", return_value=True), \
             patch.object(runner.Path, "open", return_value=MagicMock()):
            with self.assertRaises(TimeoutError):
                with runner.hsqldb_fixture():
                    self.fail("Unready fixture was used")
        process.terminate.assert_called_once()
        process.wait.assert_called_once()

    def test_owned_fixture_avoids_an_occupied_default_port(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            occupied = listener.getsockname()[1]
            port = runner.fixture_port(None, occupied)
            self.assertNotEqual(occupied, port)
            runner.require_free_port(port)
            with self.assertRaises(OSError):
                runner.fixture_port(occupied, occupied)
            self.assertEqual(occupied, runner.fixture_port(None, occupied, owned=False))
            self.assertEqual(occupied, runner.fixture_port(occupied, 3306, owned=False))
            with self.assertRaises(ValueError):
                runner.fixture_port(0, occupied, owned=False)

    def test_fixture_ports_validate_range(self):
        for port in ('-1', '65536'):
            with self.subTest(port=port), self.assertRaises(argparse.ArgumentTypeError):
                runner.port_number(port)
        self.assertEqual(0, runner.port_number('0'))
        self.assertEqual(65535, runner.port_number('65535'))

    def test_existing_port_is_not_taken_over(self):
        with patch.object(runner, "require_free_port", side_effect=OSError("occupied")), \
             patch.object(runner.subprocess, "Popen") as launch:
            with self.assertRaises(OSError):
                with runner.hsqldb_fixture():
                    self.fail("Existing fixture was taken over")
        launch.assert_not_called()

    def test_windows_port_preflight_uses_exclusive_address_ownership(self):
        listener = MagicMock()
        listener.__enter__.return_value = listener
        with patch.object(runner.socket, "socket", return_value=listener), \
             patch.object(runner.os, "name", "nt"), \
             patch.object(runner.socket, "SO_EXCLUSIVEADDRUSE", 0x100, create=True):
            runner.require_free_port(9001)
        listener.setsockopt.assert_called_once_with(runner.socket.SOL_SOCKET, 0x100, 1)
        listener.bind.assert_called_once_with(("127.0.0.1", 9001))

    def test_mysql_fixture_removes_only_an_image_it_pulled(self):
        for preexisting in (False, True):
            with self.subTest(preexisting=preexisting), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)

                def docker(command, **kwargs):
                    if command[:3] == ["docker", "image", "inspect"]:
                        return subprocess.CompletedProcess(command, 0 if preexisting else 1)
                    if command[:2] == ["docker", "exec"]:
                        return subprocess.CompletedProcess(command, 0)
                    return subprocess.CompletedProcess(command, 0)

                with patch.object(runner, "ROOT", root), patch.object(runner, "require_free_port"), \
                     patch.object(runner.subprocess, "run", side_effect=docker) as launch:
                    with runner.mysql_fixture(port=43210):
                        pass

                commands = [call.args[0] for call in launch.call_args_list]
                start = next(command for command in commands if command[:2] == ['docker', 'run'])
                self.assertIn('127.0.0.1:43210:3306', start)
                cleanup = [command for command in commands if command[:3] == ["docker", "image", "rm"]]
                self.assertEqual(not preexisting, bool(cleanup))
                container_removal = [command for command in commands if command[:3] == ["docker", "rm", "--force"]]
                self.assertEqual(1, len(container_removal))
                if cleanup:
                    self.assertLess(commands.index(container_removal[0]), commands.index(cleanup[0]))

    def test_new_mysql_image_removal_retries_a_transient_container_reference(self):
        image_removals = 0

        def docker(command, **kwargs):
            nonlocal image_removals
            if command[:3] == ["docker", "image", "rm"]:
                image_removals += 1
                return subprocess.CompletedProcess(command, 1 if image_removals == 1 else 0,
                                                   stderr="image is being used")
            return subprocess.CompletedProcess(command, 0)

        with patch.object(runner.subprocess, "run", side_effect=docker), patch.object(runner.time, "sleep") as wait:
            runner.remove_downloaded_docker_image("mysql:8.4")
        self.assertEqual(2, image_removals)
        wait.assert_called_once_with(1)

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
