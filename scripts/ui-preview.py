#!/usr/bin/env python3
"""Render database UI components without launching an IntelliJ IDE window.

Requires JDK 21 and an IntelliJ 2025.1 SDK (Gradle's cached SDK is discovered).
The IntelliJ test application supplies native action-system services; preview-only
service shadows block database work and FlatLaf approximates IDE themes.
Refreshes the five featured screenshots/ PNGs and a local gallery under ignored build/ui-preview/.
"""
import argparse
import hashlib
import html
import os
import shutil
import zipfile
from pathlib import Path
import processes as subprocess
import urllib.request

from common import ROOT, java_command
from dependencies import Dependencies, add_dependency_options, executable

FEATURED_SCREENSHOTS = {
    'side-panel.png': 'light-side-panel-340.png',
    'connection-dialog.png': 'light-connection-mysql-600.png',
    'table-view.png': 'light-table-1100.png',
    'table-editing.png': 'light-table-new-row-1100.png',
    'sql-console.png': 'light-sql-console-1100.png',
}


def gradle_wrapper():
    return r".\gradlew.bat" if os.name == 'nt' else './gradlew'


def java_args(command, args, path):
    # Java argument files avoid Windows command-length limits and quote paths with spaces.
    def quote(value):
        return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'
    path.write_text('\n'.join(map(quote, args)), encoding='utf-8')
    subprocess.run([str(command), '@' + str(path)], check=True, cwd=ROOT, timeout=180)


def cached_sdk(gradle_cache):
    for artifact in sorted(gradle_cache.glob('caches/*/transforms/*/transformed/ideaIC-2025.1*')):
        homes = [artifact, artifact / 'Contents'] + sorted(artifact.glob('*.app/Contents'))
        homes += [jar.parent.parent for jar in sorted(artifact.glob('*/lib/app-client.jar'))]
        for home in homes:
            if (home / 'lib/app-client.jar').is_file():
                return home.resolve()
    return None


def cached_test_runtime_jars(gradle_cache):
    dependencies = (
        ('junit/junit/4.13.2', 'junit-4.13.2.jar'),
        ('org.hamcrest/hamcrest-core/1.3', 'hamcrest-core-1.3.jar'),
    )
    result = []
    for module, filename in dependencies:
        matches = sorted((gradle_cache / 'caches/modules-2/files-2.1' / module).glob(f'*/{filename}'))
        if not matches:
            return None
        result.append(matches[0])
    return result


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ide-home', type=Path, help='IntelliJ SDK installation directory')
    parser.add_argument('--java-home', type=Path, help='JDK 21 installation directory')
    parser.add_argument('--compare-with', type=Path, help='Previous output directory for before/after comparison')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ui-preview',
                        help='Gallery/build directory; screenshots/ keeps only the five featured images')
    parser.add_argument('--all-previews', action='store_true',
                        help='Generate the full light/dark preview gallery; by default only the five featured screens are rendered')
    parser.add_argument('--flatlaf-jar', type=Path, help='Existing FlatLaf 3.7.2 JAR')
    parser.add_argument('--junit-jar', type=Path, help='Existing JUnit 4.13.2 JAR')
    parser.add_argument('--hamcrest-jar', type=Path, help='Existing Hamcrest Core 1.3 JAR')
    add_dependency_options(parser)
    args = parser.parse_args()
    with Dependencies(args) as deps:
        render(args, deps, parser)


def render(args, deps, parser):
    java = executable(deps.java(args.java_home), 'java').resolve()
    javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
    if not javac.is_file():
        parser.error('Select a full JDK 21 with --java-home or JAVA_HOME; javac was not found.')
    sdk = deps.ide(args.ide_home) if args.ide_home or args.binaries_dir else None
    gradle_cache = Path(args.gradle_user_home or os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')).expanduser().resolve()
    if sdk is None:
        sdk = cached_sdk(gradle_cache)
        if sdk is None:
            sdk = deps.ide()
    test_jars = cached_test_runtime_jars(gradle_cache)
    if test_jars is None:
        test_jars = []
    if args.junit_jar or args.hamcrest_jar or not test_jars:
        test_jars = [deps.file('tools/junit/junit-4.13.2.jar', 'https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar', '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3', args.junit_jar),
                     deps.file('tools/junit/hamcrest-core-1.3.jar', 'https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar', '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9', args.hamcrest_jar)]
    jars = sorted((sdk / 'lib').glob('*.jar')) + sorted((sdk / 'lib/modules').glob('*.jar'))
    if not (sdk / 'lib/app-client.jar').is_file():
        parser.error('--ide-home must point to an IntelliJ SDK with lib/app-client.jar')
    output = args.output.resolve()
    screenshots = ROOT / 'screenshots'
    if output == screenshots or screenshots in output.parents:
        parser.error('--output must be outside screenshots/; use build/ for preview build artifacts')
    baseline = args.compare_with.expanduser().resolve() if args.compare_with else None
    if baseline and (not baseline.is_dir() or baseline == output):
        parser.error('--compare-with must be an existing output directory different from --output')
    flatlaf = deps.file('tools/flatlaf/flatlaf-3.7.2.jar',
                        'https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar',
                        '917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4', args.flatlaf_jar)
    output.mkdir(parents=True, exist_ok=True)
    production = output / 'production-classes'
    preview = output / 'preview-classes'
    production.mkdir(exist_ok=True)
    preview.mkdir(exist_ok=True)
    drivers = {kind: deps.jdbc(kind) for kind in ('mysql', 'hsqldb')}
    cp = os.pathsep.join(map(str, jars + list(drivers.values()) + test_jars))
    sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    java_args(javac, ['--release', '21', '-encoding', 'UTF-8', '-cp', cp, '-d', str(production)] + list(map(str, sources)), output / 'compile-production.args')
    preview_sources = sorted((ROOT / 'scripts/ui-preview').glob('*.java'))
    java_args(javac, ['--release', '21', '-encoding', 'UTF-8', '-cp', os.pathsep.join([str(production), str(flatlaf), cp]), '-d', str(preview)] + list(map(str, preview_sources)), output / 'compile-preview.args')
    runtime = os.pathsep.join([str(preview), str(production), str(ROOT / 'src/main/resources'), str(flatlaf), cp])
    # A reused output directory should represent this invocation only.
    for theme in ('light', 'dark'):
        for path in output.glob(f'{theme}-*.png'):
            path.unlink()
        manifest = output / f'{theme}-manifest.txt'
        if manifest.exists():
            manifest.unlink()
    mode = 'all' if args.all_previews else 'featured'
    themes = ('light', 'dark') if args.all_previews else ('light',)
    for theme in themes:
        system_path = output / f'idea-system-{os.getpid()}-{theme}'
        config_path = output / f'idea-config-{os.getpid()}-{theme}'
        try:
            java_args(java, [f'-Dlattice.preview.mysql={drivers["mysql"]}',
                         f'-Dlattice.preview.hsqldb={drivers["hsqldb"]}',
                         '--add-exports=java.desktop/sun.awt=ALL-UNNAMED',
                         '--add-opens=java.base/java.lang=ALL-UNNAMED',
                         '--add-opens=java.base/java.io=ALL-UNNAMED',
                         '--add-opens=java.base/java.nio=ALL-UNNAMED',
                         '--add-opens=java.base/java.util=ALL-UNNAMED',
                         '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
                         '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
                         '--add-opens=java.desktop/java.awt.event=ALL-UNNAMED',
                         '--add-opens=java.desktop/javax.swing=ALL-UNNAMED',
                         '--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED',
                         '--add-opens=java.desktop/sun.awt=ALL-UNNAMED',
                         '--add-opens=java.desktop/sun.font=ALL-UNNAMED',
                         '--add-opens=java.desktop/sun.java2d=ALL-UNNAMED',
                         '--add-opens=java.desktop/sun.swing=ALL-UNNAMED',
                         '-Djava.awt.headless=true', '-Didea.is.unit.test=true', '-Didea.platform.prefix=Idea',
                         f'-Didea.home.path={sdk}', f'-Didea.config.path={config_path}',
                         f'-Didea.system.path={system_path}', '-cp', runtime, 'UiPreview',
                         str(output), theme, mode], output / f'{theme}.args')
        finally:
            shutil.rmtree(system_path, ignore_errors=True)
            shutil.rmtree(config_path, ignore_errors=True)
    if not args.all_previews:
        (output / 'dark-manifest.txt').write_text('', encoding='utf-8')
    publish_screenshots(output, screenshots)
    render_gallery(output, baseline)


def snapshot_names(output):
    light = (output / 'light-manifest.txt').read_text().splitlines()
    dark = (output / 'dark-manifest.txt').read_text().splitlines()
    if not light or (dark and [name.removeprefix('light-') for name in light] != [name.removeprefix('dark-') for name in dark]):
        raise ValueError('Light and dark snapshot sets differ')
    for name in light + dark:
        if Path(name).name != name or not name.endswith('.png'):
            raise ValueError(f'Invalid screenshot filename: {name}')
    return light, dark


def publish_screenshots(output, screenshots):
    light, dark = snapshot_names(output)
    names = light + dark
    # Validate the manifests and all README sources before replacing tracked images.
    for name in names:
        if not (output / name).is_file():
            raise ValueError(f'Missing required screenshot: {name}')
    for name in FEATURED_SCREENSHOTS.values():
        if name not in names:
            raise ValueError(f'Missing required screenshot: {name}')
    screenshots.mkdir(parents=True, exist_ok=True)
    for alias, source in FEATURED_SCREENSHOTS.items():
        shutil.copyfile(output / source, screenshots / alias)
    featured = set(FEATURED_SCREENSHOTS)
    for path in screenshots.glob('*.png'):
        if path.name not in featured:
            path.unlink()
    print(f'Repository screenshots: {screenshots} ({len(featured)} featured images; {len(names)} gallery variants)')


def render_gallery(output, baseline=None):
    light, dark = snapshot_names(output)
    figures = []
    artifacts = [output / filename for filename in light + dark]
    for filename in light:
        path = output / filename
        name = path.stem.removeprefix('light-')
        screen = next((kind for kind in ('connection', 'side-panel', 'sql-console', 'schema', 'inputs', 'welcome') if name.startswith(kind)), 'table')
        shots = []
        if baseline and (baseline / filename).is_file() and (baseline / filename.replace('light-', 'dark-', 1)).is_file():
            (output / 'before').mkdir(exist_ok=True)
            for theme in ('light', 'dark'):
                before_name = theme + '-' + name + '.png'
                before = output / 'before' / before_name
                shutil.copyfile(baseline / before_name, before)
                artifacts.append(before)
            shots.append(('before', 'before/', 'Before'))
        shots.append(('after', '', 'Current'))
        images = []
        for version, prefix, label in shots:
            source = html.escape(prefix + filename, quote=True)
            images.append(f'<div class="shot" data-version="{version}"><h2>{label}</h2><a href="{source}"><img src="{source}" data-name="{name}" data-prefix="{prefix}" loading="lazy" alt="{html.escape(name)} — {label}"></a></div>')
        figures.append(f'<figure data-screen="{screen}"><figcaption>{html.escape(name.replace("-", " "))}</figcaption><div class="pair">{"".join(images)}</div></figure>')
    template = (ROOT / 'scripts/ui-preview/gallery.html').read_text(encoding='utf-8')
    gallery = (template.replace('@FIGURES@', '\n'.join(figures))
               .replace('@COMPARISON_HIDDEN@', '' if baseline else 'hidden')
               .replace('@DARK_THEME_OPTION@', '<option value="dark">dark</option>' if dark else '')
               .replace('@DARK_THEME_HIDDEN@', '' if dark else 'hidden'))
    index = output / 'index.html'
    index.write_text(gallery, encoding='utf-8')
    artifacts.append(index)
    with zipfile.ZipFile(output / 'lattice-ui-previews.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in artifacts:
            archive.write(path, path.relative_to(output))
        archive.write(ROOT / 'docs/UI_PREVIEW.md', 'README.md')
    print(f'Gallery: {index}')
    print(f'Screenshots: {len(light) + len(dark)}; ZIP: {output / "lattice-ui-previews.zip"}')



if __name__ == '__main__':
    run()
