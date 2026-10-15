#!/usr/bin/env python3
"""Run H2 embedded/file, Java 8 TCP server/driver and compatibility-mode matrices."""
import argparse
import importlib.util
from pathlib import Path
import processes as subprocess
from common import ROOT, gradle_command
from dependencies import Dependencies, add_dependency_options, executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_dependency_options(parser)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--java8-home', type=Path)
    parser.add_argument('--ide-home', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/compatibility/h2')
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('one_shot', ROOT / 'scripts/one-shot-test.py')
    one_shot = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(one_shot)
    with Dependencies(args) as deps:
        java = deps.java(args.java_home)
        java8 = deps.java(args.java8_home, major=8, jdk=False)
        environment = one_shot.prepare_proxy_environment(deps.environment(java), java, deps.workspace() / 'tools')
        probe = deps.workspace() / 'java8-probe'
        probe.mkdir()
        subprocess.run([str(executable(java, 'javac')), '--release', '8', '-d', str(probe),
                        str(ROOT / 'src/test/java/com/segfault03/ideadb/Java8DriverProbe.java')], env=environment, check=True)
        command = gradle_command() + ['databaseCompatibilityTest', '-Plattice.compatibility.mode=h2',
            f'-Plattice.compatibility.java8={executable(java8, "java")}', f'-Plattice.compatibility.probe={probe}',
            f'-Plattice.compatibility.output={args.output.resolve()}',
            f'-Plattice.compatibility.cache={deps.workspace() / "jdbc"}']
        if args.ide_home:
            command += [f'-Plattice.ide.home={args.ide_home}']
        subprocess.run(command, cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    main()
