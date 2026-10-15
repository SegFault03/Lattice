#!/usr/bin/env python3
"""Run H2 functional/regression, packaging, runtime compatibility and real-IDE UI checks."""
import argparse
import os
from pathlib import Path
import sys
import processes as subprocess
from common import ROOT, gradle_command
from dependencies import Dependencies, add_dependency_options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_dependency_options(parser)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--java8-home', type=Path)
    parser.add_argument('--ide-home', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/h2-validation')
    parser.add_argument('--themes', nargs='+', default=['ExperimentalDark', 'ExperimentalLight'])
    parser.add_argument('--skip-ui', action='store_true', help='Explicitly omit the real IDE phase')
    parser.add_argument('--skip-compatibility', action='store_true', help='Explicitly omit the runtime/driver matrix')
    parser.add_argument('--ui-only', action='store_true', help='Run just the full real-IDE H2 scenario')
    args = parser.parse_args()
    if args.ui_only and args.skip_ui:
        parser.error('--ui-only cannot be combined with --skip-ui')
    if not args.skip_ui and sys.platform != 'linux':
        parser.error('Full UI automation uses Linux/X11; use --skip-ui on other hosts')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "completed.txt").unlink(missing_ok=True)
    with Dependencies(args) as deps:
        java = deps.java(args.java_home)
        environment = deps.environment(java)
        options = [f'-Plattice.ide.home={args.ide_home.resolve()}'] if args.ide_home else []
        if not args.ui_only:
            subprocess.run(gradle_command() + ['test', 'h2IntegrationTest', 'h2FallbackDriverTest', 'buildPlugin'] + options,
                           cwd=ROOT, env=environment, check=True)
            version = next(line.split('=', 1)[1].strip() for line in (ROOT / 'gradle.properties').read_text().splitlines() if line.startswith('pluginVersion='))
            subprocess.run([sys.executable, str(ROOT / 'scripts/check-release-archive.py'),
                            str(ROOT / f'build/distributions/Lattice-{version}.zip'), '--version', version],
                           cwd=ROOT, env=environment, check=True)
            if not args.skip_compatibility:
                java8 = deps.java(args.java8_home, major=8, jdk=False)
                command = [sys.executable, str(ROOT / 'scripts/h2-compatibility.py'), '--java-home', str(java),
                           '--java8-home', str(java8), '--gradle-user-home', environment['GRADLE_USER_HOME'],
                           '--output', str(args.output.resolve() / 'compatibility')]
                if args.ide_home:
                    command += ['--ide-home', str(args.ide_home)]
                subprocess.run(command, cwd=ROOT, env=environment, check=True)
        if not args.skip_ui:
            subprocess.run(gradle_command() + ['writeUiIdeHome'] + options, cwd=ROOT, env=environment, check=True)
            ide = args.ide_home or Path((ROOT / 'build/ui-ide-home.txt').read_text().strip())
            environment.update(LATTICE_UI_H2='true', LATTICE_UI_POSTGRES_PORT='0',
                LATTICE_UI_BUILD_IDE_HOME=str(ide), LATTICE_UI_IDE_HOME=str(ide),
                LATTICE_UI_STYLE_ONLY='false', LATTICE_UI_INPUTS_ONLY='false',
                LATTICE_UI_REVIEW_OUTPUT=str(args.output.resolve() / 'ui'))
            subprocess.run(['bash', str(ROOT / 'scripts/linux/review-intellij-ui.sh'), *args.themes],
                           cwd=ROOT, env=environment, check=True)
    phases = ['UI'] if args.ui_only else ['functional/regression', 'packaging'] + ([] if args.skip_compatibility else ['compatibility']) + ([] if args.skip_ui else ['UI'])
    (args.output / 'completed.txt').write_text('PASS H2: ' + ', '.join(phases) + '\n')
    print('PASS H2: ' + ', '.join(phases), flush=True)


if __name__ == '__main__':
    main()
