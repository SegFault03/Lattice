#!/usr/bin/env python3
"""Run the PostgreSQL/Java 8 feature matrix on disposable Linux Docker fixtures."""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import processes as subprocess
from common import ROOT, gradle_command
from dependencies import Dependencies, add_dependency_options, executable
from postgres import POSTGRES_VERSIONS, matrix_fixture, matrix_drivers
from test import fixture_port


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    add_dependency_options(parser)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--java8-home', type=Path)
    parser.add_argument('--ide-home', type=Path)
    parser.add_argument('--host-compiler', action='store_true', help='Build historic sources with installed GCC 14+/make (host glibc must be <= 2.41); saves VFS Docker disk space')
    parser.add_argument('--versions', nargs='+', choices=POSTGRES_VERSIONS, default=POSTGRES_VERSIONS)
    args=parser.parse_args()
    if sys.platform != 'linux':
        parser.error('The server matrix needs Linux Docker containers; external PostgreSQL integration tests work on other hosts.')
    spec=importlib.util.spec_from_file_location('one_shot', ROOT / 'scripts/one-shot-test.py')
    one_shot=importlib.util.module_from_spec(spec); spec.loader.exec_module(one_shot)
    with Dependencies(args) as deps:
        java=deps.java(args.java_home); java8=deps.java(args.java8_home, major=8, jdk=False)
        env=one_shot.prepare_proxy_environment(deps.environment(java), java, deps.workspace() / 'tools')
        probe=deps.workspace() / 'java8-probe'; probe.mkdir()
        subprocess.run([str(executable(java,'javac')), '--release','8','-d',str(probe),
                        str(ROOT/'src/test/java/com/segfault03/ideadb/Java8DriverProbe.java')],env=env,check=True)
        failures=[]
        for version in args.versions:
            print(f'PostgreSQL {version}: drivers {matrix_drivers(version)}',flush=True)
            try:
                port=fixture_port(None, 5432)
                with matrix_fixture(port,version,deps,cleanup=args.cleanup,host_compiler=args.host_compiler):
                    command=gradle_command()+['databaseCompatibilityTest','-Plattice.compatibility.mode=postgres',
                        f'-Plattice.compatibility.port={port}',f'-Plattice.compatibility.server={version}',
                        f'-Plattice.compatibility.java8={executable(java8,"java")}',f'-Plattice.compatibility.probe={probe}',
                        '-Plattice.compatibility.cleanup=false',
                        f'-Plattice.compatibility.cache={deps.workspace() / "jdbc"}']
                    if args.ide_home: command += [f'-Plattice.ide.home={args.ide_home}']
                    # Gradle builds share output classes even when fixtures/caches are isolated.
                    import fcntl
                    lock_path=ROOT / '.gradle/postgres-matrix.lock'
                    lock_path.parent.mkdir(parents=True,exist_ok=True)
                    with lock_path.open('a') as lock:
                        fcntl.flock(lock,fcntl.LOCK_EX)
                        subprocess.run(command,cwd=ROOT,env=env,check=True)
            except Exception as failure:
                failures.append((version,str(failure)))
        if failures: raise RuntimeError(f'PostgreSQL matrix failures: {failures}')
        print(f'PASS PostgreSQL matrix: {len(args.versions)*2} Java 8/feature cases',flush=True)

if __name__=='__main__': main()
