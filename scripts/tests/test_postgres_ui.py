"""The real-IDE wrapper must dispose its fixture when UI validation fails."""
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch, MagicMock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
spec=importlib.util.spec_from_file_location('postgres_ui',Path(__file__).resolve().parents[1]/'postgres-ui-review.py')
ui=importlib.util.module_from_spec(spec);spec.loader.exec_module(ui)

class PostgresUiRunnerTests(unittest.TestCase):
    def test_ui_failure_still_closes_owned_database_and_preserves_argument_paths(self):
        closed=[]
        @contextmanager
        def fixture(port,cleanup):
            self.assertEqual((port,cleanup),(15432,True))
            try: yield 'owned-test-server'
            finally: closed.append(True)
        deps=MagicMock()
        deps.java.return_value=Path('/tools/jdk')
        deps.ide.return_value=Path('/tools/IDE with spaces')
        deps.environment.return_value={'BASE_ENV':'preserved'}
        def launch(command,**kwargs):
            self.assertEqual(command[0],'bash')
            self.assertEqual(command[-1],'ExperimentalDark')
            self.assertEqual(kwargs['env']['LATTICE_UI_BUILD_IDE_HOME'],'/tools/IDE with spaces')
            self.assertEqual(kwargs['env']['LATTICE_UI_POSTGRES_PORT'],'15432')
            self.assertEqual(kwargs['env']['BASE_ENV'],'preserved')
            raise ui.subprocess.CalledProcessError(1,command)
        with patch.object(sys,'argv',['postgres-ui-review.py','--cleanup']), patch.object(sys,'platform','linux'), \
             patch.object(ui,'Dependencies') as dependencies, patch.object(ui,'postgres_fixture',fixture), \
             patch.object(ui,'fixture_port',return_value=15432), patch.object(ui.subprocess,'run',side_effect=launch):
            dependencies.return_value.__enter__.return_value=deps
            with self.assertRaises(ui.subprocess.CalledProcessError): ui.main()
        self.assertEqual(closed,[True])

    def test_unsupported_host_never_starts_dependencies_or_a_server(self):
        with patch.object(sys,'argv',['postgres-ui-review.py']), patch.object(sys,'platform','win32'), \
             patch.object(ui,'Dependencies') as dependencies, patch.object(ui,'postgres_fixture') as fixture:
            with self.assertRaises(SystemExit): ui.main()
            dependencies.assert_not_called();fixture.assert_not_called()
