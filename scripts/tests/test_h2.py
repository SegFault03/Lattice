"""H2's end-to-end runner must fail closed and run the full UI scenario by default."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location('h2_test', Path(__file__).resolve().parents[1] / 'h2-test.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class H2RunnerTests(unittest.TestCase):
    def setup_run(self, directory):
        root = Path(directory)
        (root / 'build').mkdir()
        (root / 'build/ui-ide-home.txt').write_text('/IDE with spaces')
        (root / 'gradle.properties').write_text('pluginVersion=0.0.2-alpha\n')
        deps = MagicMock()
        deps.java.side_effect = lambda explicit=None, **kwargs: Path('/tools/java8' if kwargs.get('major') == 8 else '/tools/java21')
        deps.environment.return_value = {'GRADLE_USER_HOME': '/cache with spaces', 'KEEP': 'inherited'}
        return root, deps

    def test_default_runs_all_phases_and_does_not_inherit_partial_ui_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            root, deps = self.setup_run(directory)
            calls = []
            def launch(command, **kwargs):
                calls.append((command, kwargs))
            with patch.object(sys, 'argv', ['h2-test.py', '--output', str(root / 'results')]), \
                 patch.object(sys, 'platform', 'linux'), patch.object(runner, 'ROOT', root), \
                 patch.object(runner, 'Dependencies') as dependencies, patch.object(runner.subprocess, 'run', side_effect=launch):
                dependencies.return_value.__enter__.return_value = deps
                runner.main()
            self.assertEqual(len(calls), 5)
            self.assertIn('h2IntegrationTest', calls[0][0])
            self.assertIn('h2FallbackDriverTest', calls[0][0])
            self.assertIn('buildPlugin', calls[0][0])
            self.assertIn('h2-compatibility.py', str(calls[2][0]))
            command, kwargs = calls[-1]
            self.assertEqual(command[-2:], ['ExperimentalDark', 'ExperimentalLight'])
            self.assertEqual(kwargs['env']['LATTICE_UI_H2'], 'true')
            self.assertEqual(kwargs['env']['LATTICE_UI_POSTGRES_PORT'], '0')
            self.assertEqual(kwargs['env']['LATTICE_UI_STYLE_ONLY'], 'false')
            self.assertEqual(kwargs['env']['LATTICE_UI_INPUTS_ONLY'], 'false')
            self.assertEqual(kwargs['env']['KEEP'], 'inherited')
            self.assertTrue((root / 'results/completed.txt').is_file())

    def test_failure_stops_later_phases_and_never_writes_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root, deps = self.setup_run(directory)
            (root / 'results').mkdir()
            (root / 'results/completed.txt').write_text('stale success')
            with patch.object(sys, 'argv', ['h2-test.py', '--output', str(root / 'results')]), \
                 patch.object(sys, 'platform', 'linux'), patch.object(runner, 'ROOT', root), \
                 patch.object(runner, 'Dependencies') as dependencies, \
                 patch.object(runner.subprocess, 'run', side_effect=runner.subprocess.CalledProcessError(1, ['gradle'])) as launch:
                dependencies.return_value.__enter__.return_value = deps
                with self.assertRaises(runner.subprocess.CalledProcessError): runner.main()
                self.assertEqual(launch.call_count, 1)
            self.assertFalse((root / 'results/completed.txt').exists())

    def test_conflicting_flags_never_start_dependencies(self):
        with patch.object(sys, 'argv', ['h2-test.py', '--ui-only', '--skip-ui']), patch.object(runner, 'Dependencies') as dependencies:
            with self.assertRaises(SystemExit): runner.main()
            dependencies.assert_not_called()
