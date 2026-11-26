#!/usr/bin/env python3
"""Paint the welcome-home concepts in light and dark themes.

Compiles only WelcomeConcepts.java, against the plugin classes Gradle builds (so the Java/Kotlin SQL injectors,
which need language plugins the headless platform lacks, are never recompiled here). Uses the same IntelliJ test
application and FlatLaf setup as scripts/ui-preview.py. It changes no production code and no README images.
"""
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from dependencies import Dependencies, add_dependency_options, executable
import processes as subprocess

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
    build = ROOT / 'build/welcome-home-pocs'
    build.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault('XDG_CACHE_HOME', str(build / 'runtime-cache'))
    output = args.output.resolve()
    raw = output / 'raw'
    raw.mkdir(parents=True, exist_ok=True)
    subprocess.run([preview.gradle_wrapper(), '--quiet', 'classes'], check=True, cwd=ROOT, timeout=900)
    plugin_classes = [ROOT / 'build/classes/java/main', ROOT / 'build/classes/kotlin/main', ROOT / 'src/main/resources']
    with Dependencies(args) as deps:
        java = executable(deps.java(args.java_home), 'java').resolve()
        javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
        gradle_cache = Path(args.gradle_user_home or os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
        sdk = deps.ide(args.ide_home) if args.ide_home else preview.cached_sdk(gradle_cache) or deps.ide()
        jars = sorted((sdk / 'lib').glob('*.jar')) + sorted((sdk / 'lib/modules').glob('*.jar'))
        test_jars = preview.cached_test_runtime_jars(gradle_cache)
        if not test_jars:
            parser.error('Cache the IntelliJ test runtime first; see docs/UI_PREVIEW.md.')
        flatlaf = deps.file('tools/flatlaf/flatlaf-3.8.jar',
                            'https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.8/flatlaf-3.8.jar',
                            '8d8489dee6b9512a669be7670fd2d010f6a7dcfb23b622329ce3c61f8529e76b', args.flatlaf_jar)
        cp = os.pathsep.join(map(str, plugin_classes + [flatlaf] + jars + test_jars))
        classes = build / 'presentation-classes'
        classes.mkdir(exist_ok=True)
        preview.java_args(javac, ['--release', '21', '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes),
                                  str(HERE / 'WelcomeConcepts.java')], build / 'compile.args')
        runtime = os.pathsep.join([str(classes), cp])
        opens = ['java.base/java.lang', 'java.base/java.io', 'java.base/java.nio', 'java.base/java.util',
                 'java.base/java.util.concurrent', 'java.desktop/java.awt', 'java.desktop/java.awt.event',
                 'java.desktop/javax.swing', 'java.desktop/javax.swing.plaf.basic', 'java.desktop/sun.awt',
                 'java.desktop/sun.font', 'java.desktop/sun.java2d', 'java.desktop/sun.swing',
                 'java.desktop/javax.swing.text.html']
        for theme in ('light', 'dark'):
            with tempfile.TemporaryDirectory(prefix=f'idea-{theme}-', dir=build) as temporary:
                flags = ['--add-exports=java.desktop/sun.awt=ALL-UNNAMED']
                flags += [f'--add-opens={name}=ALL-UNNAMED' for name in opens]
                flags += ['-Djava.awt.headless=true', '-Didea.is.unit.test=true',
                          f'-Didea.platform.prefix={preview.platform_prefix(sdk)}', '-Didea.force.use.core.classloader=true',
                          f'-Didea.home.path={sdk}', f'-Didea.config.path={temporary}/config',
                          f'-Didea.system.path={temporary}/system', '-cp', runtime,
                          'WelcomeConcepts', str(raw), theme]
                preview.java_args(java, flags, build / f'{theme}.args')
    print(f'Viewer: {viewer(output)}')


def viewer(output):
    """Downscale raw/ PNGs into frames/ (long edge <= 1280 px, WebP) and fill the design-primer concept viewer."""
    from PIL import Image
    frames = output / 'frames'
    frames.mkdir(exist_ok=True)
    for source in sorted((output / 'raw').glob('*.png')):
        image = Image.open(source)
        image.thumbnail((1280, 1280))
        image.save(frames / (source.stem + '.webp'), quality=82)
    spec = json.loads((HERE / 'concepts.json').read_text(encoding='utf-8'))
    variants = [('light', 1, 'Light'), ('dark', 1, 'Dark'), ('light', 2, 'Light, font 2.0'), ('dark', 2, 'Dark, font 2.0')]
    concepts = []
    for concept in spec['concepts']:
        built = {key: value for key, value in concept.items() if key not in ('states', 'motionRows')}
        built['frames'] = []
        for theme, scale, group in variants:
            for number, (key, caption, text) in enumerate(concept['states'], 1):
                name = f'{concept["id"]}-{key}-{theme}-f{scale}.webp'
                if (frames / name).exists():
                    built['frames'].append({'key': f'{key} · {group.lower()}', 'cap': f'{number} · {caption}', 'text': text,
                                            'src': f'frames/{name}', 'group': group})
        built['motion'] = [{'title': row['title'], 'spec': row['spec'],
                            'frames': [f'frames/{concept["id"]}-{row["id"]}-{i}-light-f1.webp' for i in range(3)]}
                           for row in concept.get('motionRows', [])]
        concepts.append(built)
    data = {key: spec[key] for key in ('task', 'title', 'frameWidth', 'shared', 'recommendation')}
    data['concepts'] = concepts
    template = (HERE / 'viewer.html').read_text(encoding='utf-8')
    filled = re.sub(r'const DATA = \{.*?\n\};\n', lambda _: 'const DATA = ' + json.dumps(data, indent=1, ensure_ascii=False) + ';\n',
                    template, count=1, flags=re.S)
    target = output / 'index.html'
    target.write_text(filled, encoding='utf-8')
    return target


if __name__ == '__main__':
    main()
