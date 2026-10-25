"""Check CI event routing, suite boundaries, failure aggregation and release ancestry."""
import json
import os
from pathlib import Path
import re
import shutil
import sys
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import test_suites
CI = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
RELEASE = (ROOT / '.github/workflows/release.yml').read_text(encoding='utf-8')
FULL_JOBS = ('tooling-tests', 'backend-tests', 'ui-tests', 'ide-compatibility',
             'database-compatibility', 'full-validation')


def job(workflow, name):
    return re.split(r'^  [\w-]+:\n', workflow.split(f'  {name}:\n', 1)[1], maxsplit=1, flags=re.M)[0]


def condition(body):
    return re.search(r'^    if: (.+)$', body, re.M).group(1)


class WorkflowPolicyTests(unittest.TestCase):
    def test_every_commit_runs_only_functional_checks_and_packaging(self):
        events = CI.split('permissions:', 1)[0]
        self.assertIn("branches: ['**']", events)
        self.assertIn('  pull_request:\n', events)
        self.assertIn('types: [opened, synchronize, reopened, edited, ready_for_review]', events)
        self.assertNotIn('tags:', events)
        baseline = job(CI, 'functional-build')
        self.assertIsNone(re.search(r'^    if:', baseline, re.M))
        self.assertIn('os: [ubuntu-24.04, windows-2025, macos-15]', baseline)
        self.assertIn('needs: suite-plan', baseline)
        self.assertIn('scripts/one-shot-test.py --ci-group functional-build --in-place', baseline)
        self.assertIn('--check-workflow --github-matrices', job(CI, 'suite-plan'))
        self.assertIsNone(re.search(r'^    if:', job(CI, 'suite-plan'), re.M))
        for forbidden in ('unittest discover', '--live', 'regressionTest', 'h2IntegrationTest',
                          'ui-preview.py', 'review-intellij-ui.sh', 'compatibility-only'):
            self.assertNotIn(forbidden, baseline)

    def test_all_other_suites_run_only_on_main_targeted_prs(self):
        self.assertEqual({'suite-plan', 'functional-build', *FULL_JOBS}, set(re.findall(r'^  ([\w-]+):$', CI.split('jobs:\n', 1)[1], re.M)))
        for name in FULL_JOBS:
            expression = condition(job(CI, name)).replace('always() && ', '')
            for event in ('push', 'pull_request', 'workflow_dispatch'):
                for base in ('', 'staging', 'main', 'feature/next'):
                    evaluated = expression.replace('github.event_name', repr(event)).replace('github.base_ref', repr(base))
                    with self.subTest(job=name, event=event, base=base):
                        self.assertEqual(event == 'pull_request' and base == 'main',
                                         eval(evaluated.replace('&&', 'and').replace('||', 'or'), {'__builtins__': {}}))

    def test_suites_are_grouped_without_nested_full_builds(self):
        tooling = job(CI, 'tooling-tests')
        self.assertIn('--ci-group tooling-tests --in-place', tooling)
        self.assertNotIn('buildPlugin', tooling)
        backend = job(CI, 'backend-tests')
        self.assertIn('--ci-group backend-tests --in-place', backend)
        self.assertEqual(['regressionTest'], test_suites.gradle_tasks('backend-regression'))
        self.assertEqual(['h2IntegrationTest'], test_suites.gradle_tasks('backend-h2'))
        self.assertEqual(['integrationTest', 'fallbackDriverTest'], test_suites.gradle_tasks('backend-live'))
        self.assertNotIn('scripts/h2-test.py', backend)
        self.assertNotIn('--build', backend)
        jdbc = job(CI, 'database-compatibility')
        self.assertIn('fromJSON(needs.suite-plan.outputs.database)', jdbc)
        self.assertIn('fail-fast: false', jdbc)
        self.assertTrue({'2025.1', '2025.2', '2025.3'} <=
                        {row['ide'] for row in test_suites.github_matrices()['ide']['include']})
        self.assertIn('plugin-under-test', job(CI, 'ide-compatibility'))
        self.assertIn('needs: [functional-build, suite-plan]', job(CI, 'ide-compatibility'))
        self.assertIn('--archive "${archives[0]}"', job(CI, 'ide-compatibility'))
        test_suites.check_workflow(CI)

    def test_ui_suites_cover_distinct_blockers_without_repeating_themes(self):
        ui = job(CI, 'ui-tests')
        self.assertIn('fail-fast: false', ui)
        cases = [(row['suite'].removeprefix('ui-'), row['theme'])
                 for row in test_suites.github_matrices()['ui']['include']]
        self.assertTrue({
            ('previews', 'headless'), ('hsqldb', 'ExperimentalDark'),
            ('alter-table', 'ExperimentalDark'), ('table-dialogs', 'ExperimentalLight'),
            ('cell-inspector-scaled', 'ExperimentalDark'), ('search', 'ExperimentalDark'),
            ('postgres', 'ExperimentalDark'), ('h2', 'ExperimentalDark')} <= set(cases))
        self.assertEqual({'LATTICE_UI_CELL_DETAILS_ONLY': 'true', 'LATTICE_UI_SCALE': '1.25'},
                         test_suites.suite_by_id('ui-cell-inspector-scaled')['env'])
        self.assertNotIn('LATTICE_UI_STYLE_ONLY:', ui)
        self.assertNotIn('LATTICE_UI_DIALOG_AUDIT:', ui)
        self.assertIn('--checks-only', test_suites.suite_by_id('ui-previews')['command'])
        self.assertNotIn('--all-previews', ui)
        for variable, suite in (('ALTER_TABLE_ONLY', 'alter-table'), ('CREATE_TABLE_ONLY', 'table-dialogs'),
                                ('EXPLORER_SEARCH_ONLY', 'search')):
            self.assertEqual('true', test_suites.suite_by_id(f'ui-{suite}')['env'][f'LATTICE_UI_{variable}'])
        self.assertEqual('h2-ui', test_suites.suite_by_id('ui-h2')['kind'])
        self.assertEqual('postgres-ui', test_suites.suite_by_id('ui-postgres')['kind'])
        self.assertIn('name: ui-${{ matrix.suite }}-${{ matrix.theme }}', ui)
        self.assertIn('if: always()', ui)
        self.assertIn('out/ide-tests/tests/**/log/idea.log', ui)
        self.assertIn('out/ide-tests/tests/**/log/screenshots/', ui)
        self.assertNotIn('continue-on-error', CI)

    def test_main_pr_gate_requires_every_suite_even_if_skipped_or_cancelled(self):
        gate = job(CI, 'full-validation')
        needs = re.search(r'needs: \[(.+)\]', gate).group(1).split(', ')
        self.assertEqual({'suite-plan', 'functional-build', *FULL_JOBS[:-1]}, set(needs))
        self.assertTrue(condition(gate).startswith('always() && '))
        script = textwrap.dedent(gate.split("          python3 - <<'PYCODE'\n", 1)[1].split('          PYCODE', 1)[0])
        for failing_job in needs:
            for state in ('success', 'failure', 'skipped', 'cancelled'):
                results = {name: {'result': 'success'} for name in needs}
                results[failing_job]['result'] = state
                with self.subTest(job=failing_job, state=state):
                    result = subprocess.run([sys.executable, '-c', script],
                                            env=dict(os.environ, RESULTS=json.dumps(results)),
                                            capture_output=True, text=True, timeout=10)
                    self.assertEqual(state == 'success', result.returncode == 0, result.stderr)

    def test_releases_are_new_tags_only_and_every_job_depends_on_guard(self):
        self.assertIn("tags: ['v*']", RELEASE.split('permissions:', 1)[0])
        self.assertNotIn('workflow_dispatch', RELEASE)
        for forbidden in ('database-compatibility:', 'scripts/verify-plugin.py', '--compatibility-only', '--database-compatibility-only',
                          'unittest discover', '--live', 'h2IntegrationTest', 'ui-preview.py', 'review-intellij-ui.sh'):
            self.assertNotIn(forbidden, RELEASE)
        guard = condition(job(RELEASE, 'release-ref'))
        self.assertIn("github.event_name == 'push'", guard)
        self.assertIn("github.ref_type == 'tag'", guard)
        self.assertIn('github.event.created && !github.event.deleted', guard)
        for event in ('push', 'pull_request', 'workflow_dispatch'):
            for ref_type in ('branch', 'tag'):
                for created, deleted in ((True, False), (False, False), (False, True)):
                    expression = guard.replace('github.event_name', repr(event)).replace('github.ref_type', repr(ref_type))
                    expression = expression.replace('github.event.created', repr(created)).replace('github.event.deleted', repr(deleted))
                    expression = expression.replace('&&', 'and').replace('!', 'not ')
                    with self.subTest(event=event, ref_type=ref_type, created=created, deleted=deleted):
                        self.assertEqual(event == 'push' and ref_type == 'tag' and created and not deleted,
                                         eval(expression, {'__builtins__': {}}))
        for name in ('build', 'cross-platform-checks'):
            self.assertIn('needs: release-ref', job(RELEASE, name))
        for name in ('publish', 'marketplace'):
            self.assertIn('needs: [release-ref,', job(RELEASE, name))
        self.assertIn('publish]', job(RELEASE, 'marketplace'))

    @unittest.skipUnless(shutil.which('git') and shutil.which('bash') and os.name != 'nt', 'Git and POSIX Bash required')
    def test_actual_release_guard_accepts_main_tags_and_rejects_unmerged_tags(self):
        guard = job(RELEASE, 'release-ref')
        script = textwrap.dedent(guard.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory(prefix='lattice-release-ref-') as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True, stderr=subprocess.PIPE).strip()
            git('init', '-b', 'main')
            git('config', 'user.name', 'CI test')
            git('config', 'user.email', 'ci@example.invalid')
            git('commit', '--allow-empty', '-m', 'main')
            main = git('rev-parse', 'HEAD')
            git('update-ref', 'refs/remotes/origin/main', main)
            git('tag', 'v1.0.0')
            git('tag', '-a', 'v1.0.1', '-m', 'annotated')
            git('checkout', '-b', 'feature')
            git('commit', '--allow-empty', '-m', 'unmerged feature')
            feature = git('rev-parse', 'HEAD')
            git('tag', 'v2.0.0')
            git('tag', '-a', 'v2.0.1', '-m', 'annotated unmerged')
            git('checkout', 'main')
            git('commit', '--allow-empty', '-m', 'main advances independently')
            git('update-ref', 'refs/remotes/origin/main', git('rev-parse', 'HEAD'))
            for ref, accepted in ((main, True), ('v1.0.0', True), ('v1.0.1', True),
                                  (feature, False), ('v2.0.0', False), ('v2.0.1', False)):
                with self.subTest(ref=ref):
                    result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=root,
                                            env=dict(os.environ, GITHUB_SHA=ref), capture_output=True, text=True)
                    self.assertEqual(accepted, result.returncode == 0, result.stderr)
            git('update-ref', '-d', 'refs/remotes/origin/main')
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], cwd=root,
                                    env=dict(os.environ, GITHUB_SHA=main), capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode, 'Missing main must fail closed')
