#!/usr/bin/env python3
"""Compile and paint the three isolated Swing concepts in light and dark themes.

This uses the repository's dependency discovery and the same IntelliJ test
application / FlatLaf setup as scripts/ui-preview.py. It does not run tests,
connect to databases, or update the plugin's production code or README images.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from dependencies import Dependencies, add_dependency_options, executable

spec = importlib.util.spec_from_file_location('lattice_ui_preview', ROOT / 'scripts/ui-preview.py')
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--ide-home', type=Path)
    parser.add_argument('--flatlaf-jar', type=Path)
    parser.add_argument('--output', type=Path, default=HERE / 'renders')
    add_dependency_options(parser)
    args = parser.parse_args()
    build = ROOT / 'build/create-table-pocs'
    build.mkdir(parents=True, exist_ok=True)
    font_cache = build / 'runtime-cache'
    font_cache.mkdir(exist_ok=True)
    os.environ.setdefault('XDG_CACHE_HOME', str(font_cache))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with Dependencies(args) as deps:
        java = executable(deps.java(args.java_home), 'java').resolve()
        javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
        gradle_cache = Path(args.gradle_user_home or os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
        sdk = deps.ide(args.ide_home) if args.ide_home else preview.cached_sdk(gradle_cache) or deps.ide()
        jars = sorted((sdk / 'lib').glob('*.jar')) + sorted((sdk / 'lib/modules').glob('*.jar'))
        test_jars = preview.cached_test_runtime_jars(gradle_cache)
        if not test_jars:
            parser.error('Cache the IntelliJ test runtime first; see docs/UI_PREVIEW.md.')
        flatlaf = deps.file('tools/flatlaf/flatlaf-3.7.2.jar',
                           'https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar',
                           '917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4', args.flatlaf_jar)
        cp = os.pathsep.join(map(str, [flatlaf] + jars + test_jars))
        classes = build / 'classes'
        classes.mkdir(exist_ok=True)
        sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
        preview.java_args(javac, ['--release', '21', '-encoding', 'UTF-8', '-cp', cp,
                                 '-d', str(classes)] + list(map(str, sources)), build / 'compile.args')
        presentation = build / 'presentation-classes'
        presentation.mkdir(exist_ok=True)
        # Reuse the existing offscreen dialog shell and backend blocker for the
        # production baseline. Prototype layouts themselves have no callbacks.
        preview_sources = [ROOT / 'scripts/ui-preview/DialogWrapper.java',
                           ROOT / 'scripts/ui-preview/DatabaseTaskService.java',
                           HERE / 'CreateTableConcepts.java']
        preview.java_args(javac, ['--release', '21', '-encoding', 'UTF-8', '-cp',
                                 os.pathsep.join([str(classes), cp]), '-d', str(presentation)]
                          + list(map(str, preview_sources)), build / 'compile-presentation.args')
        runtime = os.pathsep.join([str(presentation), str(classes), str(ROOT / 'src/main/resources'), cp])
        opens = ['java.base/java.lang', 'java.base/java.io', 'java.base/java.nio', 'java.base/java.util',
                 'java.base/java.util.concurrent', 'java.desktop/java.awt', 'java.desktop/java.awt.event',
                 'java.desktop/javax.swing', 'java.desktop/javax.swing.plaf.basic', 'java.desktop/sun.awt',
                 'java.desktop/sun.font', 'java.desktop/sun.java2d', 'java.desktop/sun.swing']
        for theme in ('light', 'dark'):
            with tempfile.TemporaryDirectory(prefix=f'idea-{theme}-', dir=build) as temporary:
                flags = ['--add-exports=java.desktop/sun.awt=ALL-UNNAMED']
                flags += [f'--add-opens={name}=ALL-UNNAMED' for name in opens]
                flags += ['-Djava.awt.headless=true', '-Didea.is.unit.test=true', '-Didea.platform.prefix=Idea',
                          f'-Didea.home.path={sdk}', f'-Didea.config.path={temporary}/config',
                          f'-Didea.system.path={temporary}/system', '-cp', runtime,
                          'CreateTableConcepts', str(output), theme]
                preview.java_args(java, flags, build / f'{theme}.args')
    print(f'Gallery: {HERE / "index.html"}')


if __name__ == '__main__':
    main()
