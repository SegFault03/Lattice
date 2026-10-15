"""Exercise the Linux review wrapper without launching an IDE or downloading jars."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('bash') and os.name != 'nt', 'POSIX Bash required')
class UiReviewTests(unittest.TestCase):
    def test_search_mode_routes_both_themes_and_preserves_each_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts/linux'
            scripts.mkdir(parents=True)
            runner = scripts / 'review-intellij-ui.sh'
            shutil.copyfile(ROOT / 'scripts/linux/review-intellij-ui.sh', runner)
            # Seed every fixture requested by the wrapper, so curl must never run.
            source = runner.read_text()
            for version in ('9.0.0', '8.4.0', '8.0.33'):
                artifact = root / f'build/ui-test-maven/repository/com/mysql/mysql-connector-j/{version}/mysql-connector-j-{version}.jar'
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.write_bytes(b'fixture')
            for name in re.findall(r'^fetch_jar (org/hsqldb/[^\n]+)', source, re.M):
                artifact = root / 'build/ui-test-maven/repository' / name
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.write_bytes(b'fixture')
            capture = scripts / 'capture-intellij-ui.sh'
            capture.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$@" >> build/capture-args.txt
mkdir -p build/test-results/uiScreenshotTest
printf '<testsuite/>\\n' > build/test-results/uiScreenshotTest/TEST-com.segfault03.ideadb.ui.IntellijUiScreenshotTest.xml
''')
            capture.chmod(0o755)
            result = subprocess.run(['bash', str(runner), 'ExperimentalDark', 'ExperimentalLight'], cwd=root,
                                    env=dict(os.environ, LATTICE_REVIEW_SUPERVISED='1',
                                             LATTICE_UI_EXPLORER_SEARCH_ONLY='true',
                                             LATTICE_UI_REVIEW_OUTPUT='build/ui-review/explorer-search'),
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            args = (root / 'build/capture-args.txt').read_text().splitlines()
            self.assertEqual(2, args.count('-Plattice.ui.explorerSearchOnly=true'))
            output = root / 'build/ui-review/explorer-search'
            for theme in ('ExperimentalDark', 'ExperimentalLight'):
                self.assertIn(f'-Plattice.ui.theme={theme}', args)
                self.assertIn(f'-Plattice.ui.output={output / theme}', args)
                self.assertEqual('<testsuite/>\n', (output / theme / 'test-result.xml').read_text())
                self.assertTrue((output / f'{theme}.log').exists())
                self.assertIn(f'{theme}/test-result.xml', (output / 'index.md').read_text())
