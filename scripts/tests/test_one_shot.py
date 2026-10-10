"""Regression checks for the Linux/Windows one-shot validation runner."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from argparse import Namespace
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("one_shot", SCRIPTS / "one-shot-test.py")
one_shot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(one_shot)


class OneShotTests(unittest.TestCase):
    def test_modes_split_routine_checks_from_compatibility_work(self):
        self.assertEqual(one_shot.validation_plan(Namespace(
            skip_compatibility=False, compatibility_only=False, database_compatibility_only=False)),
            (True, True, True, 8))
        self.assertEqual(one_shot.validation_plan(Namespace(
            skip_compatibility=True, compatibility_only=False, database_compatibility_only=False)),
            (True, False, False, 5))
        self.assertEqual(one_shot.validation_plan(Namespace(
            skip_compatibility=False, compatibility_only=True, database_compatibility_only=False)),
            (False, True, True, 6))
        self.assertEqual(one_shot.validation_plan(Namespace(
            skip_compatibility=False, compatibility_only=False, database_compatibility_only=True)),
            (False, False, True, 4))

    def test_declared_matrix_covers_four_mysql_servers_and_ten_hsqldb_drivers(self):
        self.assertEqual(tuple(server for server, _ in one_shot.MYSQL_MATRIX),
                         ("5.5.62", "5.6.51", "5.7.44", "8.4.2"))
        self.assertEqual(sum(len(drivers) for _, drivers in one_shot.MYSQL_MATRIX), 13)
        self.assertEqual(len(one_shot.HSQL_VERSIONS), 10)
        self.assertEqual(one_shot.IDE_VERSIONS, ("2025.1", "2025.2", "2025.3"))

    def test_isolated_source_copy_excludes_generated_ide_installations(self):
        with tempfile.TemporaryDirectory(prefix="lattice-copy-test-") as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            (source / "gradle.properties").write_text("pluginVersion=1.0.0", encoding="utf-8")
            for name in ("out", "build", ".gradle"):
                generated = source / name / "ide-cache"
                generated.mkdir(parents=True)
                (generated / "large-sdk.bin").write_bytes(b"generated SDK")
            with patch.object(one_shot, "ROOT", source):
                copied = one_shot.copy_source(root / "copy")
            self.assertTrue((copied / "gradle.properties").is_file())
            for name in ("out", "build", ".gradle"):
                self.assertFalse((copied / name).exists(), "Isolated validation must not clone generated IDEs/caches")

    def test_supported_host_guard_rejects_other_platforms(self):
        with patch.object(one_shot.sys, "platform", "darwin"):
            with self.assertRaisesRegex(one_shot.RunnerError, "Linux and Windows only"):
                one_shot.check_linux()

    def test_download_workspace_inside_checkout_is_not_recursively_copied(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradle.properties').write_text('pluginVersion=1.0.0')
            download = root / 'downloads/lattice-run-example'
            download.mkdir(parents=True)
            (download / 'tool.zip').write_bytes(b'large download')
            with patch.object(one_shot, 'ROOT', root):
                source = one_shot.copy_source(download / 'source')
            self.assertTrue((source / 'gradle.properties').is_file())
            self.assertFalse((source / 'downloads').exists())

    def test_timeout_stops_owned_process_and_preserves_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = one_shot.CommandRunner(root / 'logs', {}, one_shot.Progress())
            with self.assertRaisesRegex(one_shot.CommandFailed, 'timed out'):
                runner.run([sys.executable, '-u', '-c', "import time; print('started'); time.sleep(60)"],
                           cwd=root, name='timeout', timeout=1)
            self.assertIn('started', runner.last_log.read_text())

    def test_failed_commands_raise_and_keep_their_output_in_a_log(self):
        with tempfile.TemporaryDirectory(prefix="lattice-one-shot-test-") as directory:
            root = Path(directory)
            progress = one_shot.Progress()
            runner = one_shot.CommandRunner(root / "logs", {}, progress)
            with self.assertRaisesRegex(one_shot.CommandFailed, "exited 7"):
                runner.run([sys.executable, "-c", "print('partial output'); raise SystemExit(7)"],
                           cwd=root, name="expected-failure", timeout=10)
            self.assertIn("partial output", runner.last_log.read_text(encoding="utf-8"))

    def test_docker_image_cleanup_retries_before_reporting_a_failure(self):
        image_removals = 0

        def docker(command, **kwargs):
            nonlocal image_removals
            if command[:3] == ["docker", "image", "rm"]:
                image_removals += 1
                return one_shot.subprocess.CompletedProcess(command, 1 if image_removals == 1 else 0,
                                                            stderr="container cleanup in progress")
            return one_shot.subprocess.CompletedProcess(command, 0)

        with patch.object(one_shot.subprocess, "run", side_effect=docker), \
             patch.object(one_shot.time, "sleep") as wait:
            one_shot.remove_downloaded_image("mysql:5.5.62")
        self.assertEqual(2, image_removals)
        wait.assert_called_once_with(1)

    def test_failed_mysql_startup_keeps_logs_and_removes_its_container_without_waiting(self):
        with tempfile.TemporaryDirectory(prefix="lattice-startup-test-") as directory:
            runner = one_shot.CommandRunner(Path(directory), {}, one_shot.Progress())
            commands = []

            def docker(command, **kwargs):
                commands.append(command)
                if command[:2] == ["docker", "exec"]:
                    return one_shot.subprocess.CompletedProcess(command, 1)
                if command[:3] == ["docker", "inspect", "--format"]:
                    return one_shot.subprocess.CompletedProcess(command, 0, stdout="false 1")
                if command[:2] == ["docker", "logs"]:
                    kwargs["stdout"].write("mysqld: fixture startup failed\n")
                return one_shot.subprocess.CompletedProcess(command, 0)

            with patch.object(runner, "run"), patch.object(one_shot.subprocess, "run", side_effect=docker), \
                 patch.object(one_shot.time, "sleep", side_effect=AssertionError("An exited fixture cannot become ready")):
                with self.assertRaisesRegex(one_shot.RunnerError, "stopped before becoming ready"):
                    with one_shot.mysql_server(runner, "5.7.44"):
                        self.fail("An exited fixture was used")
            self.assertIn("fixture startup failed", (Path(directory) / "mysql-5.7.44.container.log").read_text())
            self.assertEqual(1, sum(command[:3] == ["docker", "rm", "--force"] for command in commands))

    def test_failure_report_keeps_the_command_log_after_workspace_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="lattice-one-shot-test-") as directory:
            root = Path(directory)
            logs = root / "running/logs"
            logs.mkdir(parents=True)
            (logs / "001-build.log").write_text("Gradle failed", encoding="utf-8")
            report = one_shot.preserve_failure(root, root / "temporary", logs,
                                               "plugin build", RuntimeError("nonzero exit"))
            self.assertEqual("Gradle failed", (report / "logs/001-build.log").read_text())
            text = (report / "failure.txt").read_text()
            self.assertIn("Failed step: plugin build", text)
            self.assertIn("Saved command logs:", text)

    def test_runtime_extractor_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory(prefix="lattice-one-shot-test-") as directory:
            root = Path(directory)
            archive = root / "unsafe.tar.gz"
            with one_shot.tarfile.open(archive, "w:gz") as bundle:
                member = one_shot.tarfile.TarInfo("../escaped.txt")
                member.size = 1
                import io
                bundle.addfile(member, io.BytesIO(b"x"))
            with self.assertRaisesRegex(one_shot.RunnerError, "escapes"):
                one_shot.extract_tar(archive, root / "tools")
            self.assertFalse((root / "escaped.txt").exists())

    def test_proxy_truststore_setup_creates_the_temporary_tools_directory(self):
        with tempfile.TemporaryDirectory(prefix="lattice-one-shot-test-") as directory:
            root = Path(directory)
            java_home = root / "jdk"
            tools = root / "temporary/tools"
            cert = root / "proxy.crt"
            cacerts = java_home / "lib/security/cacerts"
            keytool = java_home / "bin/keytool"
            cacerts.parent.mkdir(parents=True)
            keytool.parent.mkdir(parents=True)
            cacerts.write_text("trust store", encoding="utf-8")
            keytool.touch()
            cert.write_text("proxy certificate", encoding="utf-8")
            env = {"CODEX_PROXY_CERT": str(cert), "HTTPS_PROXY": "https://proxy.example:8080"}
            with patch.object(one_shot.subprocess, "run") as import_certificate:
                result = one_shot.prepare_proxy_environment(env, java_home, tools)
            self.assertTrue((tools / "proxy-truststore").is_file())
            self.assertIn("-Dhttps.proxyHost=proxy.example", result["JAVA_TOOL_OPTIONS"])
            import_certificate.assert_called_once()


if __name__ == "__main__":
    unittest.main()
