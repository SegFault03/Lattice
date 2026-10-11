"""Check that platform launchers remain usable after directory consolidation."""
from pathlib import Path
import ast
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class ScriptLayoutTests(unittest.TestCase):
    def test_public_scripts_and_docs_do_not_embed_workstation_paths(self):
        pattern = re.compile(r'(?i)[A-Z]:[\\/](?:code|Users)[\\/]|/(?:home|Users)/[^/\\\s<]+')
        for folder in (SCRIPTS, SCRIPTS.parent / 'docs'):
            for path in folder.rglob('*'):
                if path.is_file() and path.suffix in ('.py', '.ps1', '.sh', '.md', '.html'):
                    with self.subTest(path=str(path.relative_to(SCRIPTS.parent))):
                        self.assertIsNone(pattern.search(path.read_text(encoding='utf-8-sig')))

    def test_production_python_commands_use_owned_processes(self):
        for path in SCRIPTS.rglob('*.py'):
            if 'tests' in path.relative_to(SCRIPTS).parts or path.name == 'processes.py':
                continue
            tree = ast.parse(path.read_text(encoding='utf-8-sig'))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    self.assertNotIn('subprocess', [item.name for item in node.names], str(path))
                elif isinstance(node, ast.ImportFrom):
                    self.assertNotEqual('subprocess', node.module, str(path))

    def test_generated_artifacts_are_ignored_and_required_source_assets_are_allowed(self):
        ignored = ['build/test.png', 'out/plugin.jar', 'downloads/idea.zip', 'lib/hsqldb.jar',
                   'screenshots/extra.png', 'screenshots/gallery/index.html', 'scratch/server.exe',
                   'scratch/runtime.dll', 'scratch/source.tar.gz', 'scripts/__pycache__/common.pyc']
        allowed = ['screenshots/side-panel.png', 'screenshots/connection-dialog.png',
                   'screenshots/table-view.png', 'screenshots/table-editing.png', 'screenshots/sql-console.png',
                   'gradle/wrapper/gradle-wrapper.jar', 'assets/icon.png',
                   'src/main/resources/META-INF/pluginIcon.png', 'scripts/windows/test.ps1']
        # One-shot validation deliberately copies source without .git metadata.
        with tempfile.TemporaryDirectory() as directory:
            shutil.copyfile(SCRIPTS.parent / '.gitignore', Path(directory) / '.gitignore')
            subprocess.run(['git', 'init', '--quiet', directory], check=True, capture_output=True, timeout=30)
            result = subprocess.run(['git', '-c', 'core.excludesFile=', 'check-ignore', '--no-index', '-z', '--stdin'],
                                    input=('\0'.join(ignored + allowed) + '\0').encode(), cwd=directory,
                                    capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(set(ignored), set(result.stdout.decode().rstrip('\0').split('\0')))

    def test_platform_files_are_in_their_own_directories(self):
        self.assertFalse(list(SCRIPTS.glob('*.ps1')))
        self.assertFalse(list(SCRIPTS.glob('*.sh')))
        self.assertTrue((SCRIPTS / 'windows/native_mysql.py').is_file())
        self.assertTrue((SCRIPTS / 'windows/windows-script-dependencies.py').is_file())

    @unittest.skipUnless(os.name == 'nt', 'Windows launcher execution')
    def test_python_launchers_work_from_an_unrelated_directory(self):
        commands = sorted(path for path in (SCRIPTS / 'windows').glob('*.ps1')
                          if path.name not in ('capture-intellij-ui.ps1', 'review-intellij-ui.ps1', 'windows-common.ps1'))
        self.assertEqual(9, len(commands))
        with tempfile.TemporaryDirectory(prefix='lattice launcher ') as directory:
            for command in commands:
                for interpreter in ([], ['-Python', sys.executable]):
                    with self.subTest(command=command.name, interpreter=interpreter):
                        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                                 '-File', str(command), *interpreter, '--help'],
                                                cwd=directory, text=True, capture_output=True, timeout=30)
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertIn('usage:', result.stdout)

    @unittest.skipUnless(os.name == 'nt', 'Windows launcher execution')
    def test_missing_python_reports_failure(self):
        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                 str(SCRIPTS / 'windows/test.ps1'), '-Python', 'lattice-python-not-installed.exe', '--help'],
                                text=True, capture_output=True, timeout=30)
        self.assertNotEqual(0, result.returncode)

    @unittest.skipUnless(os.name == 'nt', 'Windows launcher execution')
    def test_launchers_preserve_failure_exit_status(self):
        result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                 str(SCRIPTS / 'windows/test.ps1'), '--not-a-lattice-option'],
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(2, result.returncode, result.stderr)

    def test_windows_bootstrap_can_import_shared_helpers(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(SCRIPTS / 'windows/windows-script-dependencies.py'), '--help'],
                                    cwd=directory, text=True, capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('configuration', result.stdout)


if __name__ == '__main__':
    unittest.main()
