"""Exercise future catalog changes through both the CI and local entry paths."""
from argparse import Namespace
from contextlib import redirect_stderr, redirect_stdout
from contextlib import nullcontext
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import test_suites
import test as test_runner

spec = importlib.util.spec_from_file_location('parity_one_shot', SCRIPTS / 'one-shot-test.py')
one_shot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(one_shot)
WORKFLOW = (SCRIPTS.parent / '.github/workflows/ci.yml').read_text(encoding='utf-8')


def full_windows_args(**overrides):
    return Namespace(skip_compatibility=False, compatibility_only=False,
                     database_compatibility_only=False, windows_ui=True,
                     platform='windows', **overrides)


class SuiteParityTests(unittest.TestCase):
    def test_full_windows_plan_contains_every_supported_ci_suite_and_explicit_exclusions(self):
        catalog = test_suites.load_catalog()
        actual = {s['id'] for s in one_shot.selected_suites(full_windows_args())}
        expected = {s['id'] for s in catalog['suites'] if 'windows' in s['platforms']}
        self.assertEqual(expected, actual)
        for suite in catalog['suites']:
            if suite['id'] not in actual:
                self.assertTrue(suite['unsupported']['windows'])
        self.assertEqual(actual, {s['id'] for s in test_suites.check_workflow(WORKFLOW)})

    def test_new_windows_command_automatically_enters_ci_group_and_local_execution(self):
        catalog = deepcopy(test_suites.load_catalog())
        entry = dict(id='future-regression', group='backend-tests', phase='base', kind='command',
                     platforms=['linux', 'windows', 'macos'], ci_hosts=['windows'], requires=[],
                     command=['{python}', 'scripts/future-regression.py', '--strict'])
        catalog['suites'].append(entry)
        with patch.object(test_suites, 'load_catalog', return_value=catalog), \
             patch.object(test_suites, 'host_platform', return_value='windows'):
            local = one_shot.selected_suites(full_windows_args())
            ci = one_shot.selected_suites(Namespace(ci_group='backend-tests'))
            self.assertIn(entry, local)
            self.assertIn(entry, ci)
            test_suites.check_workflow(WORKFLOW, catalog)
            context = Namespace(args=Namespace(ide_home=None, binaries_dir=None, download_dir=None),
                                source=Path('checkout'), runner=MagicMock(), java_home=None,
                                java8_home=None, jdbc_cache=Path('drivers'), temp=Path('scratch'), env={})
            one_shot.execute_suite(entry, context)
        self.assertEqual([sys.executable, 'scripts/future-regression.py', '--strict'],
                         context.runner.run.call_args.args[0])

    def test_changed_gradle_selectors_flow_to_both_local_launchers_and_ci_executor(self):
        catalog = deepcopy(test_suites.load_catalog())
        entry = test_suites.suite_by_id('backend-regression', catalog)
        entry['tasks'].append('futureRegressionTest')
        context = Namespace(args=None, source=Path('checkout'), runner=MagicMock())
        args = Namespace(live=False, build=False, version=None, ide_home=None, notes_file=None)
        with patch.object(test_suites, 'load_catalog', return_value=catalog), \
             patch.object(one_shot, 'run_gradle') as execute:
            self.assertIn('futureRegressionTest', test_runner.command(args))
            one_shot.execute_suite(entry, context)
            self.assertIn('futureRegressionTest', execute.call_args.args)

    def test_ci_host_selection_and_live_task_ownership_do_not_duplicate_h2(self):
        with patch.object(test_suites, 'host_platform', return_value='windows'):
            ci = one_shot.selected_suites(Namespace(ci_group='backend-tests'))
        self.assertEqual({'backend-regression', 'backend-h2', 'backend-h2-driver'}, {s['id'] for s in ci})
        args = Namespace(live=True, integration_only=True, suite='backend-live', build=False,
                         version=None, ide_home=None, notes_file=None)
        tasks = test_runner.command(args)
        self.assertIn('integrationTest', tasks)
        self.assertIn('fallbackDriverTest', tasks)
        self.assertNotIn('h2IntegrationTest', tasks)
        self.assertNotIn('postgres', test_suites.suite_by_id('backend-live')['fixtures']['windows'])

    def test_matrix_driver_selections_and_persistent_cache_are_forwarded_to_java(self):
        context = Namespace(args=Namespace(cleanup=True, binaries_dir=Path('assets'), mysql_port=None),
                            source=Path('checkout'), runner=MagicMock(), java8_home=Path('java8'),
                            probe_classes=Path('probe'), jdbc_cache=Path('assets/jdbc-cache'), native=False)
        with patch.object(one_shot, 'MYSQL_MATRIX', (('8.4.2', ('26.7.0',)),)), \
             patch.object(one_shot, 'mysql_server', return_value=nullcontext()), \
             patch.object(one_shot, 'run_gradle') as execute:
            one_shot.run_database_suite(test_suites.suite_by_id('jdbc-mysql'), context)
        properties = execute.call_args.kwargs['properties']
        self.assertIn('-Plattice.compatibility.drivers=26.7.0', properties)
        self.assertIn('-Plattice.compatibility.cleanup=false', properties)
        self.assertIn(f'-Plattice.compatibility.cache={context.jdbc_cache}', properties)

    def test_ui_and_ide_matrix_additions_use_catalog_values_on_every_registered_host(self):
        catalog = deepcopy(test_suites.load_catalog())
        ui = test_suites.suite_by_id('ui-hsqldb', catalog)
        ui.update(theme='ExperimentalLight', ci_hosts=['linux', 'windows'])
        new_ide = deepcopy(test_suites.suite_by_id('ide-2025.3', catalog))
        new_ide.update(id='ide-future', version='future')
        catalog['suites'].append(new_ide)
        matrices = test_suites.github_matrices(catalog)
        self.assertIn(dict(suite='ui-hsqldb', os='windows-2025', theme='ExperimentalLight'), matrices['ui']['include'])
        self.assertIn(dict(suite='ide-future', os='ubuntu-24.04', ide='future'), matrices['ide']['include'])
        with patch.object(test_suites, 'load_catalog', return_value=catalog):
            self.assertIn(new_ide, one_shot.selected_suites(full_windows_args()))
        test_suites.check_workflow(WORKFLOW, catalog)

    def test_ui_executor_uses_shared_theme_flags_and_resets_inherited_scenario_flags(self):
        suite = deepcopy(test_suites.suite_by_id('ui-table-dialogs'))
        suite['theme'] = 'ExperimentalDark'
        suite['env']['LATTICE_UI_SCALE'] = '1.5'
        context = Namespace(args=Namespace(ide_home=None, binaries_dir=None, download_dir=None, cleanup=True),
                            source=Path('checkout'), runner=MagicMock(), deps=MagicMock(),
                            env={'GRADLE_USER_HOME': 'gradle', 'LATTICE_UI_ALTER_TABLE_ONLY': 'true'},
                            java_home=Path('jdk'), temp=Path('scratch'), stamp='test')
        context.deps.ide.return_value = Path('ide')
        one_shot.run_ui_suite(suite, context)
        environment = context.runner.run.call_args.kwargs['env']
        self.assertEqual('ExperimentalDark', environment['UI_THEME'])
        self.assertEqual('1.5', environment['LATTICE_UI_SCALE'])
        self.assertEqual('true', environment['LATTICE_UI_CREATE_TABLE_ONLY'])
        self.assertEqual('false', environment['LATTICE_UI_ALTER_TABLE_ONLY'])

    def test_guard_rejects_ci_bypasses_missing_dispatch_and_reintroduced_manual_matrix(self):
        mutations = [
            WORKFLOW.replace('--ci-group backend-tests --in-place', '--ci-group tooling-tests --in-place'),
            WORKFLOW.replace('--ci-group backend-tests --in-place', '--ci-group backend-tests --in-place\n      - name: Unregistered tests\n        run: ./gradlew newRegressionTest'),
            WORKFLOW.replace('matrix: ${{ fromJSON(needs.suite-plan.outputs.ui) }}', 'matrix:\n        suite: [ui-hsqldb]'),
            WORKFLOW.replace('  suite-plan:\n', "  suite-plan:\n    if: github.base_ref == 'main'\n"),
            WORKFLOW.replace('  backend-tests:\n', '  unregistered-tests:\n'),
            WORKFLOW.replace('--ci-group backend-tests --in-place', '--ci-group backend-tests --in-place || true'),
            WORKFLOW.replace('      - name: Run shared UI scenario\n', '      - name: Run shared UI scenario\n        if: false\n'),
        ]
        for workflow in mutations:
            with self.subTest(workflow=workflow[-70:]), self.assertRaises(ValueError):
                test_suites.check_workflow(workflow)

    def test_excluding_windows_requires_a_reason_and_linux_only_adapters_cannot_claim_windows(self):
        for mutation in ('missing-reason', 'linux-adapter'):
            catalog = deepcopy(test_suites.load_catalog())
            if mutation == 'missing-reason':
                entry = test_suites.suite_by_id('backend-regression', catalog)
                entry['platforms'].remove('windows')
                entry['ci_hosts'].remove('windows')
            else:
                entry = test_suites.suite_by_id('ui-postgres', catalog)
                entry['platforms'].append('windows')
                del entry['unsupported']['windows']
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'suites.json'
                path.write_text(json.dumps(catalog), encoding='utf-8')
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    test_suites.load_catalog(path)

    def test_plan_and_workflow_guard_do_not_provision_dependencies(self):
        with patch.object(sys, 'argv', ['one-shot-test.py', '--list-suites', '--platform', 'windows', '--windows-ui', '--check-workflow']), \
             patch.object(one_shot, 'Dependencies', side_effect=AssertionError('metadata must be offline')), \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, one_shot.main())
        self.assertIn('jdbc-h2', output.getvalue())
        self.assertIn('Historic PostgreSQL', output.getvalue())

    def test_guard_detects_a_local_runner_that_drops_a_supported_suite(self):
        incomplete = test_suites.select_suites('windows')[1:]
        with patch.object(sys, 'argv', ['one-shot-test.py', '--check-workflow']), \
             patch.object(one_shot, 'selected_suites', return_value=incomplete), \
             patch.object(one_shot, 'Dependencies') as dependencies, self.assertRaises(SystemExit) as failure:
            one_shot.main()
        self.assertEqual(2, failure.exception.code)
        dependencies.assert_not_called()

    def test_live_adapter_uses_catalog_native_mysql_version(self):
        suite = deepcopy(test_suites.suite_by_id('backend-live'))
        suite['mysql_native_version'] = 'future'
        context = Namespace(args=Namespace(mysql_port=None, hsqldb_port=None, binaries_dir=None,
                                          runtime_dir=None, sevenzip=None, docker=None,
                                          mysql_homes={}, cleanup=True),
                            source=Path('checkout'), runner=MagicMock(), deps=MagicMock(),
                            java_home=Path('jdk'), temp=Path('scratch'),
                            env={'GRADLE_USER_HOME': 'gradle'}, native=True)
        with patch('windows.native_mysql.mysql_home', return_value=Path('mysql')) as resolve, \
             patch.object(test_suites, 'host_platform', return_value='windows'):
            one_shot.execute_suite(suite, context)
        resolve.assert_called_once_with(context.deps, 'future', None)

    def test_new_hsqldb_only_live_suite_does_not_require_mysql_provisioning(self):
        suite = deepcopy(test_suites.suite_by_id('backend-live'))
        suite.update(id='future-hsqldb', requires=['java21'], fixtures={host: ['hsqldb'] for host in suite['platforms']})
        del suite['mysql_native_version']
        del suite['mysql_docker_image']
        context = Namespace(args=Namespace(mysql_port=None, hsqldb_port=None, binaries_dir=None,
                                          runtime_dir=None, sevenzip=None, docker=None,
                                          mysql_homes={}, cleanup=True),
                            source=Path('checkout'), runner=MagicMock(), deps=MagicMock(),
                            java_home=Path('jdk'), temp=Path('scratch'),
                            env={'GRADLE_USER_HOME': 'gradle'}, native=True)
        with patch('windows.native_mysql.mysql_home') as resolve, \
             patch.object(test_suites, 'host_platform', return_value='windows'):
            one_shot.execute_suite(suite, context)
        resolve.assert_not_called()
        self.assertIn('--hsqldb', context.runner.run.call_args.args[0])
        self.assertNotIn('--mysql', context.runner.run.call_args.args[0])

    def test_occupied_explicit_fixture_port_fails_before_provisioning(self):
        with patch.object(sys, 'argv', ['one-shot-test.py', '--suite', 'backend-live', '--in-place', '--mysql-port', '3306']), \
             patch.object(one_shot, 'require_free_port', side_effect=one_shot.RunnerError('Port is occupied')), \
             patch.object(one_shot, 'Dependencies') as dependencies, \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(1, one_shot.main())
        dependencies.return_value.__enter__.assert_not_called()
        dependencies.return_value.workspace.assert_not_called()

    def test_explicit_verifier_archive_cannot_be_silently_replaced_by_a_build(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'candidate.zip'
            archive.touch()
            with patch.object(sys, 'argv', ['one-shot-test.py', '--suite', 'functional-build', '--archive', str(archive)]), \
                 patch.object(one_shot, 'Dependencies') as dependencies, redirect_stderr(io.StringIO()), \
                 self.assertRaises(SystemExit) as failure:
                one_shot.main()
            self.assertEqual(2, failure.exception.code)
            dependencies.assert_not_called()

    def test_unsupported_suite_and_verifier_without_exact_archive_fail_before_downloads(self):
        cases = [['--suite', 'jdbc-postgres', '--list-suites', '--platform', 'windows'],
                 ['--suite', 'ide-2025.1', '--in-place']]
        for flags in cases:
            with self.subTest(flags=flags), patch.object(sys, 'argv', ['one-shot-test.py', *flags]), \
                 patch.object(one_shot, 'Dependencies') as dependencies, self.assertRaises(SystemExit) as failure:
                one_shot.main()
            self.assertEqual(2, failure.exception.code)
            dependencies.assert_not_called()


if __name__ == '__main__':
    unittest.main()
