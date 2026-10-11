"""Reusable, owned development dependencies. Python 3.11+, no third-party modules."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import processes as subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

from common import cache_directory, resolved_path


def add_dependency_options(parser, *, cleanup=False):
    parser.add_argument('--download-dir', type=Path,
                        help='Download/cache parent; only directories created by this run are cleaned')
    parser.add_argument('--cleanup', action='store_true', default=cleanup,
                        help='Remove this run\'s downloaded dependencies and scratch files, including on failure')
    parser.add_argument('--no-cleanup', dest='cleanup', action='store_false',
                        help='Keep downloaded dependencies and scratch files for reuse')
    parser.add_argument('--binaries-dir', type=Path,
                        help='Optional existing asset collection (ides/, mysql/, jdbc/, tools/)')
    parser.add_argument('--gradle-user-home', type=Path,
                        help='Reuse this Gradle cache; otherwise honor GRADLE_USER_HOME or use an owned cache')


def executable(home, name):
    return Path(home) / 'bin' / (name + '.exe' if os.name == 'nt' else name)


def java_version(home, major=21, jdk=True):
    java = executable(home, 'java')
    if not java.is_file() or (jdk and not executable(home, 'javac').is_file()):
        return False
    result = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=False)
    import re
    match = re.search(r'version "(?:1\.)?(\d+)', result.stdout + result.stderr)
    return result.returncode == 0 and match is not None and int(match[1]) == major


def checksum(path, algorithm='sha256'):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(url, destination, expected=None, algorithm='sha256'):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    part = destination.with_suffix(destination.suffix + '.part')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'Lattice-development/1.0'})
        with urllib.request.urlopen(request, timeout=180) as response, part.open('wb') as output:
            shutil.copyfileobj(response, output)
        if expected and checksum(part, algorithm) != expected.lower():
            raise ValueError(f'Checksum mismatch: {url}')
        part.replace(destination)
    finally:
        part.unlink(missing_ok=True)
    return destination


def extract(archive, destination):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as bundle:
            for item in bundle.infolist():
                name = item.filename.replace('\\', '/')
                target = (destination / name).resolve()
                if not target.is_relative_to(destination) or ':' in name or ((item.external_attr >> 16) & 0o170000) == 0o120000:
                    raise ValueError(f'Unsafe ZIP member: {item.filename}')
            bundle.extractall(destination)
    else:
        with tarfile.open(archive) as bundle:
            bundle.extractall(destination, filter='data')


class Dependencies:
    def __init__(self, args):
        self.base = resolved_path(getattr(args, 'download_dir', None) or cache_directory())
        self.binaries = getattr(args, 'binaries_dir', None)
        if self.binaries:
            self.binaries = resolved_path(self.binaries)
            if not self.binaries.is_dir():
                raise ValueError('--binaries-dir must be an existing directory')
        self.cleanup = getattr(args, 'cleanup', False)
        self.path = None
        gradle_home = getattr(args, 'gradle_user_home', None) or os.environ.get('GRADLE_USER_HOME')
        self.gradle_home = resolved_path(gradle_home) if gradle_home else None

    def __enter__(self):
        return self

    def workspace(self):
        if self.path is None:
            self.base.mkdir(parents=True, exist_ok=True)
            self.path = Path(tempfile.mkdtemp(prefix='lattice-run-', dir=self.base)).resolve()
            print(f'Dependency workspace: {self.path}', flush=True)
        return self.path

    def __exit__(self, *exc):
        if self.path and self.cleanup:
            # Never recursively delete the caller's download parent or supplied assets.
            if self.path.resolve().parent != self.base or not self.path.name.startswith('lattice-run-'):
                raise ValueError('Refusing cleanup outside the owned workspace')
            def writable(function, path, error):
                if not isinstance(error[1], PermissionError):
                    raise error[1]
                os.chmod(path, os.stat(path).st_mode | stat.S_IWRITE)
                function(path)
            shutil.rmtree(self.path, onerror=writable)
        elif self.path:
            print(f'Retained dependencies/scratch: {self.path}', flush=True)

    def existing(self, relative):
        candidates = ([self.binaries / relative] if self.binaries else [])
        if self.binaries:
            candidates += sorted(self.binaries.glob(f'lattice-run-*/{relative}'), reverse=True)
        candidates += [self.base / relative]
        candidates += sorted(self.base.glob(f'lattice-run-*/{relative}'), reverse=True)
        return next((path for path in candidates if path.exists()), None)

    def file(self, relative, url, expected=None, explicit=None, algorithm='sha256'):
        path = resolved_path(explicit) if explicit else self.existing(relative)
        if path:
            if not path.is_file():
                raise ValueError(f'Dependency file does not exist: {path}')
            if expected and checksum(path, algorithm) != expected.lower():
                raise ValueError(f'Dependency checksum mismatch: {path}')
            return path
        return download(url, self.workspace() / relative, expected, algorithm)

    def jdbc(self, kind, explicit=None):
        """Resolve the production driver; an explicit JAR may select another version."""
        if kind == 'hsqldb':
            name = 'hsqldb-2.7.4.jar'
            artifact = 'org/hsqldb/hsqldb/2.7.4/' + name
            expected = '5fab2bb4384ac06b762638c8fa2740c944b8d080e4796c0c6c2af8b90dd4e5ad'
        elif kind == 'mysql':
            name = 'mysql-connector-j-26.7.0.jar'
            artifact = 'com/mysql/mysql-connector-j/26.7.0/' + name
            expected = '69084713593a4aa8d07c383619b9639276f08bccf8faf1c562178147d389b1e1'
        else:
            raise ValueError('Unknown JDBC driver: ' + kind)
        return self.file(f'jdbc/{kind}/{name}', 'https://repo.maven.apache.org/maven2/' + artifact,
                         expected=None if explicit else expected, explicit=explicit)

    def java(self, explicit=None, major=21, jdk=True):
        if explicit:
            home = resolved_path(explicit)
            if not java_version(home, major, jdk):
                raise ValueError(f'Java path must contain a {"JDK" if jdk else "runtime"} {major}: {home}')
            return home
        candidates = []
        env_home = os.environ.get('JAVA_HOME' if major == 21 else 'JAVA8_HOME')
        if env_home:
            candidates.append(Path(env_home))
        path_java = shutil.which('java')
        if path_java:
            candidates.append(Path(path_java).resolve().parent.parent)
        relative = f'runtimes/java-{major}'
        existing = self.existing(relative)
        if existing and (existing / '.complete').is_file():
            candidates += [path.parent.parent for path in existing.rglob('java.exe' if os.name == 'nt' else 'java')]
        if self.binaries:
            candidates += sorted(self.binaries.glob('ides/*/jbr'))
            candidates += sorted(self.binaries.glob('runtimes/*'))
        for home in candidates:
            if java_version(home, major, jdk):
                return home.resolve()
        system = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'mac'}.get(platform.system())
        arch = {'amd64': 'x64', 'x86_64': 'x64', 'arm64': 'aarch64', 'aarch64': 'aarch64'}.get(platform.machine().lower())
        if not system or not arch:
            raise ValueError('Unsupported Java download host; supply --java-home')
        image = 'jdk' if jdk else 'jre'
        url = f'https://api.adoptium.net/v3/assets/latest/{major}/hotspot?architecture={arch}&image_type={image}&os={system}&vendor=eclipse'
        request = urllib.request.Request(url, headers={'User-Agent': 'Lattice-development/1.0', 'Accept': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            asset = json.load(response)[0]['binary']['package']
        target = self.workspace() / relative
        archive = download(asset['link'], self.workspace() / 'archives' / asset['name'], asset['checksum'])
        extract(archive, target)
        for path in target.rglob('java.exe' if os.name == 'nt' else 'java'):
            home = path.parent.parent
            if java_version(home, major, jdk):
                (target / '.complete').touch()
                return home.resolve()
        raise ValueError('Downloaded runtime has no usable Java binary')

    def environment(self, java_home):
        configured = self.gradle_home or (Path(os.environ['GRADLE_USER_HOME']) if os.environ.get('GRADLE_USER_HOME') else None)
        gradle = resolved_path(configured) if configured else self.workspace() / 'gradle-home'
        result = {**os.environ, 'JAVA_HOME': str(java_home), 'GRADLE_USER_HOME': str(gradle),
                  'LATTICE_DEV_CACHE': str(self.workspace() / 'development-cache')}
        # Windows Java's dual-stack sockets can time out even when IPv4/Python
        # downloads succeed. Configure the wrapper JVM, before Gradle starts.
        options = result.get('JAVA_TOOL_OPTIONS', '')
        if os.name == 'nt' and '-Djava.net.preferIPv4Stack=' not in options:
            result['JAVA_TOOL_OPTIONS'] = (options + ' -Djava.net.preferIPv4Stack=true').strip()
        return result

    def git(self, explicit=None):
        if explicit:
            command = resolved_path(explicit)
            if not command.is_file():
                raise ValueError('--git must be a Git executable')
            return str(command)
        command = shutil.which('git')
        if command:
            return command
        existing = self.existing('tools/git/cmd/git.exe')
        if existing:
            return str(existing)
        if os.name != 'nt':
            raise ValueError('Install Git or supply --git; portable Git download supports Windows x64')
        with urllib.request.urlopen(urllib.request.Request('https://api.github.com/repos/git-for-windows/git/releases/latest', headers={'User-Agent': 'Lattice-development/1.0'}), timeout=60) as response:
            release = json.load(response)
        asset = next(a for a in release['assets'] if a['name'].startswith('MinGit-') and a['name'].endswith('-64-bit.zip') and 'busybox' not in a['name'])
        digest = asset.get('digest', '')
        if not digest.startswith('sha256:'):
            raise ValueError('Git release has no SHA-256; supply --git')
        archive = download(asset['browser_download_url'], self.workspace() / 'archives' / asset['name'], digest[7:])
        extract(archive, self.workspace() / 'tools/git')
        return str(self.workspace() / 'tools/git/cmd/git.exe')

    def ide(self, explicit=None, version='2025.1'):
        if explicit:
            home = resolved_path(explicit)
            if not (home / 'product-info.json').is_file():
                raise ValueError(f'IDE path must contain product-info.json: {home}')
            return home
        relative = f'ides/{version}'
        existing = self.existing(relative)
        owned_cache = existing and existing.is_relative_to(self.base) and any(p.name.startswith('lattice-run-') for p in existing.parents)
        if existing and (not owned_cache or (existing / '.complete').is_file()):
            homes = [existing] + [p.parent for p in existing.rglob('product-info.json')]
            for home in homes:
                if (home / 'product-info.json').is_file():
                    return home.resolve()
        system = {'Windows': 'windowsZip', 'Linux': 'linux', 'Darwin': 'linux'}.get(platform.system())
        if system == 'windowsZip' and platform.machine().lower() not in ('amd64', 'x86_64'):
            raise ValueError('Portable Windows IDE downloads require x64; supply --ide-home on ARM64')
        if system == 'linux' and platform.machine().lower() in ('aarch64', 'arm64'):
            system += 'ARM64'
        with urllib.request.urlopen('https://data.services.jetbrains.com/products/releases?code=IIC&type=release', timeout=60) as response:
            releases = json.load(response)['IIC']
        release = next(item for item in releases if item['version'] == version)
        package = release['downloads'][system]
        link = package['link']
        checksum_url = package['checksumLink']
        with urllib.request.urlopen(checksum_url, timeout=60) as response:
            expected = response.read(1024).decode('ascii').split()[0]
        archive = download(link, self.workspace() / 'archives' / link.rsplit('/', 1)[1], expected)
        target = self.workspace() / relative
        extract(archive, target)
        homes = list(target.rglob('product-info.json'))
        if len(homes) != 1:
            raise ValueError('Downloaded IDE has no unique product-info.json')
        (target / '.complete').touch()
        return homes[0].parent.resolve()


@contextmanager
def temporary_environment(environment):
    previous = {name: os.environ.get(name) for name in environment}
    os.environ.update(environment)
    try:
        yield
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
