"""Regression checks for workflow routing and the actual release ancestry guard."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
CI = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
RELEASE = (ROOT / '.github/workflows/release.yml').read_text(encoding='utf-8')


def job(workflow, name):
    return re.split(r'^  [\w-]+:\n', workflow.split(f'  {name}:\n', 1)[1], maxsplit=1, flags=re.M)[0]


def condition(body):
    return re.search(r'^    if: (.+)$', body, re.M).group(1)


class WorkflowPolicyTests(unittest.TestCase):
    def test_baseline_runs_on_all_branch_pushes_and_pr_merge_commits(self):
        events = CI.split('permissions:', 1)[0]
        self.assertIn("branches: ['**']", events)
        self.assertIn('  pull_request:\n', events)
        self.assertIn('types: [opened, synchronize, reopened, edited, ready_for_review]', events)
        self.assertNotIn('tags:', events)
        baseline = job(CI, 'commit-checks')
        self.assertIsNone(re.search(r'^    if:', baseline, re.M))
        self.assertIn('os: [ubuntu-24.04, windows-2025, macos-15]', baseline)
        self.assertIn('scripts/test.py --live --mysql --hsqldb --postgres --build', baseline)
        self.assertIn('scripts/test.py --build', baseline)
        self.assertIn('scripts/check-release-archive.py', baseline)
        self.assertIn('scripts/ui-preview.py --all-previews', baseline)
        self.assertIn('review-intellij-ui.sh ExperimentalDark', baseline)
        self.assertIn('review-intellij-ui.sh ExperimentalLight', baseline)
        self.assertIn('scripts/postgres-ui-review.py --java-home', baseline)
        self.assertIn('--ide-home "$(cat build/ui-ide-home.txt)" --cleanup', baseline)
        self.assertIn('build/ui-review-postgres/', baseline)

    def test_ui_suites_remain_independent_and_fail_the_job_without_hiding_evidence(self):
        baseline = job(CI, 'commit-checks')
        for name in ('Run dark IDE functional and regression UI tests',
                     'Run light IDE styling and key-header regression tests',
                     'Run PostgreSQL real-IDE functional and regression UI tests'):
            step = baseline.split(f'      - name: {name}\n', 1)[1].split('      - name:', 1)[0]
            expression = re.search(r'^        if: (.+)$', step, re.M).group(1)
            self.assertIn('!cancelled()', expression)
            self.assertIn("matrix.os == 'ubuntu-24.04'", expression)
            self.assertIn("steps.ui_display.outcome == 'success'", expression)
            self.assertIn("steps.swing_previews.outcome == 'success'", expression)
            self.assertNotIn('continue-on-error', step)
        self.assertIn('out/ide-tests/tests/**/log/idea.log', baseline)
        self.assertIn('out/ide-tests/tests/**/log/screenshots/', baseline)

    def test_compatibility_runs_only_for_prs_to_staging_or_main(self):
        for name in ('ide-compatibility', 'database-compatibility', 'compatibility'):
            expression = condition(job(CI, name))
            # Evaluate the actual event/base routing clause over the event matrix.
            route = expression.replace('always() && ', '').split(" && needs.", 1)[0]
            for event in ('push', 'pull_request', 'workflow_dispatch'):
                for base in ('', 'feature/next', 'staging', 'main'):
                    evaluated = route.replace('github.event_name', repr(event)).replace('github.base_ref', repr(base))
                    with self.subTest(job=name, event=event, base=base):
                        self.assertEqual(event == 'pull_request' and base in ('staging', 'main'),
                                         eval(evaluated.replace('&&', 'and').replace('||', 'or'), {'__builtins__': {}}))
        self.assertIn("ide: ['2025.1', '2025.2', '2025.3']", job(CI, 'ide-compatibility'))
        self.assertIn('--database-compatibility-only', job(CI, 'database-compatibility'))
        self.assertIn('plugin-under-test', job(CI, 'ide-compatibility'))

    def test_releases_are_new_tags_only_and_every_job_depends_on_guard(self):
        self.assertIn("tags: ['v*']", RELEASE.split('permissions:', 1)[0])
        self.assertNotIn('workflow_dispatch', RELEASE)
        for forbidden in ('database-compatibility:', 'scripts/verify-plugin.py', '--compatibility-only', '--database-compatibility-only'):
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
