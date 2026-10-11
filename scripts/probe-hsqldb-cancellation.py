#!/usr/bin/env python3
"""Run the in-memory HSQLDB cancellation diagnostic with a bounded JVM lifetime."""
import argparse
from pathlib import Path
import processes as subprocess

from common import ROOT
from dependencies import Dependencies, add_dependency_options, executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('driver_jar', nargs='?', type=Path, help='HSQLDB driver JAR; find/download 2.7.4 when omitted')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--timeout', type=float, default=45)
    add_dependency_options(parser, cleanup=True)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    if args.driver_jar and not args.driver_jar.is_file():
        parser.error('Driver JAR does not exist: ' + str(args.driver_jar))
    with Dependencies(args) as deps:
        java = executable(deps.java(args.java_home), 'java')
        jar = deps.jdbc('hsqldb', args.driver_jar)
        try:
            return subprocess.run([str(java), '--class-path', str(jar.resolve()),
                                   str(ROOT / 'src/test/java/com/segfault03/ideadb/service/HsqlCancellationProbe.java')],
                                  cwd=ROOT, timeout=args.timeout, check=False).returncode
        except subprocess.TimeoutExpired:
            print(f'Cancellation probe exceeded {args.timeout:g}s; its JVM was terminated.')
            return 124


if __name__ == '__main__':
    raise SystemExit(main())
