#!/usr/bin/env python3
"""Run the full production IntelliJ UI scenario against an owned PostgreSQL 18 fixture."""
import argparse
import os
from pathlib import Path
import sys
import processes as subprocess
from common import ROOT
from dependencies import Dependencies, add_dependency_options
from postgres import postgres_fixture, TEST_PASSWORD
from test import fixture_port


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_dependency_options(parser)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--ide-home', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ui-review-postgres')
    parser.add_argument('--themes', nargs='+', default=['ExperimentalDark'])
    args = parser.parse_args()
    if sys.platform != 'linux':
        parser.error('This wrapper requires Linux Docker and X11; use an external PostgreSQL fixture with the native UI runner elsewhere.')
    with Dependencies(args) as deps:
        java = deps.java(args.java_home)
        ide = deps.ide(args.ide_home)
        port = fixture_port(None, 5432)
        env = {**deps.environment(java),
               'LATTICE_UI_POSTGRES_PORT': str(port), 'LATTICE_UI_POSTGRES_PASSWORD': TEST_PASSWORD,
               'LATTICE_UI_BUILD_IDE_HOME': str(ide), 'LATTICE_UI_IDE_HOME': str(ide),
               'LATTICE_UI_REVIEW_OUTPUT': str(args.output.resolve())}
        with postgres_fixture(port, cleanup=args.cleanup):
            subprocess.run(['bash', str(ROOT / 'scripts/linux/review-intellij-ui.sh'), *args.themes],
                           cwd=ROOT, env=env, check=True)

if __name__ == '__main__':
    main()
