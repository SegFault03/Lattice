from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("dev_deploy", Path(__file__).resolve().parents[1] / "dev-deploy.py")
deploy = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = deploy
spec.loader.exec_module(deploy)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="lattice deploy test ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def ide(self, name="IDE with spaces", system=None, build="251.100.1", product="IC"):
        system = system or deploy.platform_name()
        directory = self.root / name
        if system == "macOS" and directory.suffix != ".app":
            directory = directory.with_name(directory.name + ".app")
        home = directory / "Contents" if system == "macOS" else directory
        info_dir = home / "Resources" if system == "macOS" else home
        launcher = "../MacOS/idea" if system == "macOS" else "bin/idea64.exe" if system == "Windows" else "bin/idea.sh"
        java = "../jbr/bin/java" if system == "macOS" else "jbr/bin/java.exe" if system == "Windows" else "jbr/bin/java"
        vm_file = "../bin/idea.vmoptions" if system == "macOS" else "bin/idea64.exe.vmoptions" if system == "Windows" else "bin/idea64.vmoptions"
        info = {"name": "IntelliJ IDEA", "productCode": product, "version": "2025.1", "buildNumber": build,
                "dataDirectoryName": "IdeaIC2025.1", "launch": [{"os": system, "arch": "amd64", "launcherPath": launcher,
                "javaExecutablePath": java, "vmOptionsFilePath": vm_file, "additionalJvmArguments": ["-Didea.paths.selector=IdeaIC2025.1"]}]}
        info_dir.mkdir(parents=True)
        (info_dir / "product-info.json").write_text(json.dumps(info), encoding="utf-8-sig" if system == "Windows" else "utf-8")
        for relative in (launcher, java, vm_file):
            path = (info_dir / relative).resolve()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("", encoding="utf-8")
        (home / "jbr/release").write_text('JAVA_VERSION="21.0.6"\n', encoding="utf-8")
        with patch.object(deploy, "platform_name", return_value=system):
            return deploy.read_ide(directory)

    def plugin(self, path, plugin_id=deploy.PLUGIN_ID, version="old", jar_name="implementation.jar"):
        jar = path if path.suffix == ".jar" else path / "lib" / jar_name
        jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{plugin_id}</id><version>{version}</version><idea-version since-build='251'/></idea-plugin>")
        return jar

    def archive(self, version="0.0.1-alpha", extra=None, symlink=False):
        contents = io.BytesIO()
        with zipfile.ZipFile(contents, "w") as jar:
            jar.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{deploy.PLUGIN_ID}</id><version>{version}</version><idea-version since-build='251'/></idea-plugin>")
            for name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/mysql-connector-j-9.0.0-LICENSE.txt",
                         "licenses/hsqldb-LICENSE.txt", "licenses/protobuf-LICENSE.txt"):
                jar.writestr(name, "notice")
            jar.writestr("sample.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x41")
        path = self.root / "new plugin.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Lattice/lib/Lattice.jar", contents.getvalue())
            archive.writestr("Lattice/lib/mysql-connector-j-9.0.0.jar", "driver")
            archive.writestr("Lattice/lib/hsqldb-2.7.3.jar", "driver")
            if extra:
                archive.writestr(extra, "bad")
            if symlink:
                entry = zipfile.ZipInfo("Lattice/")
                entry.create_system = 3
                entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(entry, "/unrelated")
        return path

    def quiet_progress(self):
        progress = deploy.Progress(False)
        progress.emit = MagicMock()
        return progress

    def cli_args(self, ide):
        return ["--ide-home", str(ide.home), "--java-home", str(ide.home / "jbr"),
                "--plugins-dir", str(self.root / "user/plugins"), "--no-color"]

    def test_metadata_windows_linux_and_macos_app_layouts_and_compatibility(self):
        for system in ("Windows", "Linux", "macOS"):
            with self.subTest(system=system):
                name = system + (".app" if system == "macOS" else " IDEA")
                ide = self.ide(name=name, system=system)
                self.assertTrue(ide.launcher.is_file())
                self.assertEqual((251, 100, 1), ide.build)
                self.assertEqual(bool(ide.app), system == "macOS")
        with self.assertRaisesRegex(ValueError, "2025.1"):
            self.ide("old IDE", build="243.100.1")
        with self.assertRaisesRegex(ValueError, "Not an IntelliJ"):
            self.ide("another product", product="PY")

    def test_selects_running_installation_then_newest_and_rejects_ambiguity(self):
        old, new = self.ide("old"), self.ide("new", build="253.100.1")
        running = deploy.Process(123, old.launcher, (str(old.launcher),))
        self.assertEqual(old, deploy.choose_ide([new, old], [running]))
        self.assertEqual(new, deploy.choose_ide([new, old], []))
        with self.assertRaisesRegex(ValueError, "Several"):
            deploy.choose_ide([old, new], [running, deploy.Process(456, new.launcher, ())])
        with self.assertRaisesRegex(ValueError, "No compatible"):
            deploy.choose_ide([], [])

    def test_discovery_handles_toolbox_depth_and_duplicate_roots(self):
        ide = self.ide("Toolbox/apps/IDEA/ch-0/2025.1")
        with patch.object(deploy, "discovery_roots", return_value=[self.root / "Toolbox", ide.home]):
            self.assertEqual([ide], deploy.discover_ides([]))

    def test_process_matching_uses_exact_installation_and_excludes_java_helpers(self):
        ide = self.ide()
        java = ide.home / "jbr/bin/java"
        self.assertTrue(deploy.is_idea_process(deploy.Process(1, ide.launcher, ()), ide))
        self.assertTrue(deploy.is_idea_process(deploy.Process(2, java, ("java", f"-Didea.home.path={ide.home}", "com.intellij.idea.Main")), ide))
        self.assertFalse(deploy.is_idea_process(deploy.Process(3, java, ("java", f"-Didea.home.path={ide.home}", "org.jetbrains.jps.cmdline.Launcher")), ide))
        self.assertFalse(deploy.is_idea_process(deploy.Process(4, java, ("java", f"-Didea.home.path={ide.home}-other", "com.intellij.idea.Main")), ide))
        self.assertFalse(deploy.is_idea_process(deploy.Process(5, ide.launcher, (str(ide.launcher), "installPlugins", "plugin-id")), ide))

    @unittest.skipUnless(os.name == "nt", "Windows native argument parser")
    def test_windows_command_line_preserves_paths_with_spaces(self):
        argv = deploy.windows_arguments('"C:/Program Files/IDEA/bin/idea64.exe" "-Didea.plugins.path=C:/Users/test user/plugins"')
        self.assertEqual("C:/Program Files/IDEA/bin/idea64.exe", argv[0])
        self.assertEqual("C:/Users/test user/plugins", deploy.vm_properties(argv)["idea.plugins.path"])

    def test_default_paths_all_platforms_and_linux_xdg(self):
        home, selector = self.root / "profile", "IdeaIC2025.1"
        for system, expected in (("win32", home / "AppData/Roaming/JetBrains" / selector / "plugins"),
                                 ("darwin", home / "Library/Application Support/JetBrains" / selector / "plugins"),
                                 ("linux", home / ".local/share/JetBrains" / selector)):
            self.assertEqual(expected, deploy.default_paths(selector, system, home, {})[1])
        environment = {"XDG_CONFIG_HOME": str(home / "config"), "XDG_DATA_HOME": str(home / "data")}
        self.assertEqual((home / "config/JetBrains" / selector, home / "data/JetBrains" / selector),
                         deploy.default_paths(selector, "linux", home, environment))

    def test_custom_properties_vm_options_and_live_profile_precedence(self):
        ide, home = self.ide(), self.root / "profile"
        with patch.dict(os.environ, {"APPDATA": str(home / "Roaming")}, clear=True), patch.object(deploy.Path, "home", return_value=home):
            config, _ = deploy.default_paths(ide.selector)
            config.mkdir(parents=True)
            (config / "idea.properties").write_text("idea.config.path=${user.home}/custom-config\nidea.plugins.path=${idea.config.path}/plugins\n", encoding="utf-8")
            actual_config, plugins = deploy.ide_paths(ide, [])
            self.assertEqual(home / "custom-config", actual_config)
            self.assertEqual(actual_config / "plugins", plugins)
            info_dir = ide.home / "Resources" if ide.app else ide.home
            vm_file = deploy.installed_path(ide.home, info_dir, ide.launch["vmOptionsFilePath"])
            vm_file.write_text(f"-Didea.plugins.path={home.as_posix()}/vm-plugins\n", encoding="utf-8")
            self.assertEqual(home / "vm-plugins", deploy.ide_paths(ide, [])[1])
            active = deploy.Process(5, ide.launcher, ())
            with patch.object(deploy, "live_properties", return_value={"idea.plugins.path": str(home / "live-plugins")}):
                self.assertEqual(home / "live-plugins", deploy.ide_paths(ide, [active])[1])
                self.assertEqual(home / "override", deploy.ide_paths(ide, [active], home / "override")[1])
            (config / "idea.properties").write_text("idea.plugins.path=${unknown}/plugins\n", encoding="utf-8")
            vm_file.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Unresolved"):
                deploy.ide_paths(ide, [])

    def test_jdk_discovery_uses_java_21_and_rejects_other_versions(self):
        ide = self.ide()
        self.assertEqual(ide.home / "jbr", deploy.find_java(ide, ide.home / "jbr"))
        (ide.home / "jbr/release").write_text('JAVA_VERSION="24.0.2"', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Java 21"):
            deploy.find_java(ide, ide.home / "jbr")

    def test_staged_deployment_replaces_only_lattice_and_supports_legacy_jar_installation(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                plugins = self.root / str(legacy) / "plugins"
                old = plugins / ("old-lattice.jar" if legacy else "Lattice")
                self.plugin(old)
                unrelated = self.plugin(plugins / "another-plugin", "org.example.unrelated")
                untouched = unrelated.read_bytes()
                stage = deploy.stage_plugin(self.archive(), "0.0.1-alpha", plugins)
                self.assertEqual(plugins.parent, stage.parent)
                self.assertFalse(stage.is_relative_to(plugins))
                deploy.deploy_plugin(stage, plugins)
                self.assertEqual({deploy.PLUGIN_ID}, deploy.descriptor_ids(plugins / "Lattice"))
                self.assertTrue((stage / "previous-installation").exists())
                self.assertEqual(untouched, unrelated.read_bytes())
                if legacy:
                    self.assertFalse(old.exists())
                deploy.cleanup_stage(stage, plugins.parent)
                self.assertFalse(stage.exists())

    def test_replacement_failure_restores_old_plugin_and_rollback_failure_retains_backup(self):
        plugins = self.root / "plugins"
        old_jar = self.plugin(plugins / "Lattice")
        before = old_jar.read_bytes()
        stage = deploy.stage_plugin(self.archive(), "0.0.1-alpha", plugins)
        real_replace = os.replace
        def fail_new(source, target):
            if source == stage / "Lattice":
                raise PermissionError("simulated replacement failure")
            return real_replace(source, target)
        with patch.object(deploy.os, "replace", side_effect=fail_new):
            with self.assertRaises(PermissionError):
                deploy.deploy_plugin(stage, plugins)
        self.assertEqual(before, old_jar.read_bytes())
        def fail_new_and_restore(source, target):
            if source in {stage / "Lattice", stage / "previous-installation"}:
                raise PermissionError("simulated rollback failure")
            return real_replace(source, target)
        with patch.object(deploy.os, "replace", side_effect=fail_new_and_restore):
            with self.assertRaises(deploy.RecoveryError):
                deploy.deploy_plugin(stage, plugins)
        self.assertTrue((stage / "previous-installation/lib/implementation.jar").is_file())

    def test_rejects_unrelated_target_duplicates_unsafe_zip_and_unowned_cleanup(self):
        plugins = self.root / "plugins"
        self.plugin(plugins / "Lattice", "org.example.other")
        with self.assertRaisesRegex(ValueError, "unrecognized"):
            deploy.existing_plugin(plugins)
        self.plugin(plugins / "Lattice")
        self.plugin(plugins / "duplicate-lattice")
        with self.assertRaisesRegex(ValueError, "Multiple"):
            deploy.existing_plugin(plugins)
        for extra, symlink in (("../escape", False), (None, True)):
            with self.assertRaises(ValueError):
                deploy.stage_plugin(self.archive(extra=extra, symlink=symlink), "0.0.1-alpha", self.root / "not-created/plugins")
        self.assertFalse((self.root / "not-created").exists())
        with self.assertRaisesRegex(ValueError, "unowned"):
            deploy.cleanup_stage(plugins, plugins.parent)
        self.assertTrue(plugins.exists())

    def test_dry_run_is_read_only_and_has_ordered_progress(self):
        ide = self.ide()
        with patch.object(deploy, "scan_processes", return_value=[]), patch.object(deploy, "build_plugin") as build, \
             patch.object(deploy, "stage_plugin") as stage, patch.object(deploy.subprocess, "run") as run, \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, deploy.main(self.cli_args(ide) + ["--dry-run"]))
        build.assert_not_called()
        stage.assert_not_called()
        run.assert_not_called()
        self.assertFalse((self.root / "user").exists())
        self.assertLess(output.getvalue().index("[1/7] OK"), output.getvalue().index("[2/7] SKIP"))
        self.assertIn("[7/7] SKIP", output.getvalue())

    def test_failed_tooling_or_build_gate_never_stops_or_deploys(self):
        ide = self.ide()
        for tooling_failure in (False, True):
            with self.subTest(tooling_failure=tooling_failure), \
                 patch.object(deploy, "scan_processes", return_value=[]), \
                 patch.object(deploy.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "tests") if tooling_failure else None), \
                 patch.object(deploy, "build_plugin", side_effect=subprocess.CalledProcessError(1, "gradle")) as build, \
                 patch.object(deploy, "stop_ide") as stop, patch.object(deploy, "stage_plugin") as stage, \
                 redirect_stdout(io.StringIO()):
                self.assertEqual(1, deploy.main(self.cli_args(ide)))
                stop.assert_not_called()
                stage.assert_not_called()
                if tooling_failure:
                    build.assert_not_called()

    def test_invalid_archive_never_stops_running_ide_or_changes_plugin(self):
        ide = self.ide()
        old = self.plugin(self.root / "user/plugins/Lattice")
        before = old.read_bytes()
        process = deploy.Process(321, ide.launcher, ())
        with patch.object(deploy, "scan_processes", return_value=[process]), \
             patch.object(deploy.subprocess, "run"), patch.object(deploy, "build_plugin", return_value=self.archive(extra="../escape")), \
             patch.object(deploy, "stop_ide") as stop, patch.object(deploy, "start_ide") as start, \
             redirect_stdout(io.StringIO()):
            self.assertEqual(1, deploy.main(self.cli_args(ide)))
        stop.assert_not_called()
        start.assert_not_called()
        self.assertEqual(before, old.read_bytes())

    def test_closed_ide_stays_closed_after_successful_deployment(self):
        ide = self.ide()
        version = deploy.read_properties(deploy.ROOT / "gradle.properties")["pluginVersion"]
        with patch.object(deploy, "scan_processes", return_value=[]), patch.object(deploy.subprocess, "run"), \
             patch.object(deploy, "build_plugin", return_value=self.archive(version)), \
             patch.object(deploy, "stop_ide") as stop, patch.object(deploy, "start_ide") as start, \
             redirect_stdout(io.StringIO()):
            self.assertEqual(0, deploy.main(self.cli_args(ide)))
        stop.assert_not_called()
        start.assert_not_called()
        self.assertEqual({deploy.PLUGIN_ID}, deploy.descriptor_ids(self.root / "user/plugins/Lattice"))

    def test_running_ide_stops_before_deployment_and_reopens_even_on_deployment_error(self):
        ide = self.ide()
        process = deploy.Process(321, ide.launcher, ())
        version = deploy.read_properties(deploy.ROOT / "gradle.properties")["pluginVersion"]
        for fails in (False, True):
            events = []
            real_deploy = deploy.deploy_plugin
            def replace(stage, plugins):
                events.append("deploy")
                if fails:
                    raise PermissionError("simulated deployment failure")
                real_deploy(stage, plugins)
            with self.subTest(fails=fails), patch.object(deploy, "scan_processes", side_effect=[[process], [process], []]), \
                 patch.object(deploy.subprocess, "run"), patch.object(deploy, "build_plugin", return_value=self.archive(version)), \
                 patch.object(deploy, "stop_ide", side_effect=lambda *args: events.append("stop")), \
                 patch.object(deploy, "deploy_plugin", side_effect=replace), \
                 patch.object(deploy, "start_ide", side_effect=lambda *args: events.append("start")), \
                 redirect_stdout(io.StringIO()):
                self.assertEqual(1 if fails else 0, deploy.main(self.cli_args(ide)))
            self.assertEqual(["stop", "deploy", "start"], events)

    def test_shutdown_timeout_aborts_without_force_kill(self):
        ide = self.ide()
        process = deploy.Process(321, ide.launcher, ())
        with patch.object(deploy, "scan_processes", return_value=[process]), \
             patch.object(deploy, "request_close") as close, patch.object(deploy.time, "monotonic", side_effect=[0, 0, 2]), \
             patch.object(deploy.os, "kill") as kill:
            with self.assertRaises(TimeoutError):
                deploy.stop_ide(ide, [process], 1, self.quiet_progress())
        close.assert_called_once_with(process)
        kill.assert_not_called()

    def test_launch_uses_same_installation_and_project_and_checks_process_start(self):
        for system in dict.fromkeys((deploy.platform_name(), "macOS")):
            with self.subTest(system=system):
                ide = self.ide("launch " + system + (".app" if system == "macOS" else ""), system=system)
                process = deploy.Process(999, ide.launcher, ())
                with patch.object(deploy, "ROOT", self.root / "project"), \
                     patch.object(deploy.sys, "platform", "darwin" if system == "macOS" else sys.platform), \
                     patch.object(deploy.subprocess, "Popen") as launch, \
                     patch.object(deploy, "scan_processes", return_value=[process]), patch.object(deploy.time, "sleep"):
                    deploy.start_ide(ide, self.root / "project with spaces", 1, self.quiet_progress())
                command = launch.call_args.args[0]
                if system == "macOS":
                    self.assertEqual(["open", "-a", str(ide.app), "--args"], command[:-1])
                else:
                    self.assertEqual(str(ide.launcher), command[0])
                self.assertEqual(str(self.root / "project with spaces"), command[-1])
                self.assertEqual(ide.home, launch.call_args.kwargs["cwd"])

    def test_restart_rejects_a_different_profile_and_keeps_recovery_files(self):
        ide = self.ide()
        process = deploy.Process(321, ide.launcher, ())
        paths = (self.root / "config", self.root / "plugins")
        with patch.object(deploy, "ROOT", self.root / "project"), patch.object(deploy.subprocess, "Popen"), \
             patch.object(deploy, "scan_processes", return_value=[process]), patch.object(deploy.time, "sleep"), \
             patch.object(deploy, "ide_paths", return_value=(paths[0], self.root / "other-plugins")):
            with self.assertRaisesRegex(RuntimeError, "different profile"):
                deploy.start_ide(ide, None, 1, self.quiet_progress(), paths)

        self.plugin(self.root / "user/plugins/Lattice")
        version = deploy.read_properties(deploy.ROOT / "gradle.properties")["pluginVersion"]
        with patch.object(deploy, "scan_processes", side_effect=[[process], [process], []]), \
             patch.object(deploy.subprocess, "run"), patch.object(deploy, "build_plugin", return_value=self.archive(version)), \
             patch.object(deploy, "stop_ide"), patch.object(deploy, "start_ide", side_effect=RuntimeError("different profile")), \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, deploy.main(self.cli_args(ide)))
        backups = list((self.root / "user").glob(".lattice-deploy-*/previous-installation"))
        self.assertEqual(1, len(backups))
        self.assertIn("Recovery files retained", output.getvalue())

    def test_platform_shutdown_requests_use_only_the_selected_process(self):
        process = deploy.Process(123, self.root / "idea", ())
        with patch.object(deploy.sys, "platform", "win32"), patch.object(deploy, "request_windows_close") as close:
            deploy.request_close(process)
        close.assert_called_once_with(process)
        with patch.object(deploy.sys, "platform", "darwin"), patch.object(deploy.subprocess, "run") as run:
            deploy.request_close(process)
        self.assertEqual(["osascript", "-l", "JavaScript"], run.call_args.args[0][:3])
        self.assertEqual("123", run.call_args.args[0][-1])
        with patch.object(deploy.sys, "platform", "linux"), patch.object(deploy.shutil, "which", return_value="wmctrl"), \
             patch.object(deploy.subprocess, "check_output", return_value="0x01 0 123 host IDEA\n0x02 0 456 host other\n"), \
             patch.object(deploy.subprocess, "run") as run, patch.object(deploy.os, "kill") as kill:
            deploy.request_close(process)
        run.assert_called_once_with(["wmctrl", "-ic", "0x01"], check=True)
        kill.assert_not_called()
        with patch.object(deploy.sys, "platform", "linux"), patch.object(deploy.shutil, "which", return_value=None), \
             patch.object(deploy.os, "kill") as kill:
            deploy.request_close(process)
        kill.assert_called_once_with(123, deploy.signal.SIGTERM)


if __name__ == "__main__":
    unittest.main()
