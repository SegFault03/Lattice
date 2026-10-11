"""Windows ZIP MySQL fixtures with fresh owned data; never use an installation's data."""
from contextlib import contextmanager
import os
from pathlib import Path
import re
import shutil
import struct
import processes as subprocess
import time
import uuid
import urllib.request

from dependencies import checksum, download, extract, executable
from common import resolved_path


def mysql_home(deps, version, explicit=None):
    directory = resolved_path(explicit) if explicit else deps.existing(f'mysql/{version}')
    if directory:
        if directory.is_file() and directory.name.lower() == 'mysqld.exe':
            directory = directory.parent.parent
        candidates = [directory] + [p.parent.parent for p in directory.rglob('mysqld.exe')]
        home = next((p for p in candidates if executable(p, 'mysqld').is_file()), None)
        if home is None:
            raise ValueError(f'MySQL {version} path has no bin/mysqld.exe: {directory}')
        return home
    if os.name != 'nt':
        raise ValueError('Native auto-download fixtures support Windows x64; use Docker on Linux')
    name = f'mysql-{version}-winx64.zip'
    series = '.'.join(version.split('.')[:2])
    url = f'https://cdn.mysql.com/archives/mysql-{series}/{name}'
    with urllib.request.urlopen(url + '.md5', timeout=60) as response:
        expected = response.read(1024).decode('ascii').split()[0]
    if not re.fullmatch('[0-9a-fA-F]{32}', expected):
        raise ValueError('MySQL published an invalid archive checksum')
    archive = download(url, deps.workspace() / 'archives' / name, expected, 'md5')
    directory = deps.workspace() / 'mysql' / version
    extract(archive, directory)
    return mysql_home(deps, version, directory)


def runtime_environment(deps, home, version, runtime_dir=None, sevenzip=None):
    env = os.environ.copy()
    runtime = 'vc2010' if version.startswith(('5.5.', '5.6.')) else 'vc2013' if version.startswith('5.7.') else 'vc14'
    source = resolved_path(runtime_dir) if runtime_dir else deps.existing(f'runtimes/{runtime}')
    if runtime_dir and not source.is_dir():
        raise ValueError('--runtime-dir must contain runtime DLLs')
    target = deps.workspace() / 'runtime-dlls' / runtime

    def copy_dlls(directory):
        target.mkdir(parents=True, exist_ok=True)
        for path in directory.rglob('*'):
            if not path.is_file():
                continue
            match = re.search(r'(msvc[pr]\d+|vcruntime\d+(?:_\d+)?|concrt\d+)', path.name, re.I)
            if match and (path.suffix.lower() == '.dll' or 'x64' in path.name.lower() or 'amd64' in path.name.lower()):
                shutil.copyfile(path, target / (match[1].lower() + '.dll'))
        env['PATH'] = str(target) + os.pathsep + env.get('PATH', '')

    if source:
        copy_dlls(source)
    result = subprocess.run([str(executable(home, 'mysqld')), '--no-defaults', '--version'], env=env,
                            capture_output=True, text=True, timeout=30)
    if result.returncode == 0:
        if version not in result.stdout + result.stderr:
            raise ValueError(f'Expected MySQL {version}: {result.stdout}{result.stderr}')
        return env
    if result.returncode not in (0xC0000135, -1073741515, 0xC000007B, -1073741701):
        raise ValueError(f'MySQL {version} version check failed ({result.returncode}): {result.stdout}{result.stderr}')
    # A missing CRT fails before mysqld can emit diagnostics. Extract Microsoft
    # packages into our private PATH; do not install anything system-wide.
    urls = {
        'vc2010': 'https://download.microsoft.com/download/1/6/5/165255E7-1014-4D0A-B094-B6A430A6BFFC/vcredist_x64.exe',
        'vc2013': 'https://download.microsoft.com/download/2/E/6/2E61CFA4-993B-4DD4-91DA-3737CD5CD6E3/vcredist_x64.exe',
        'vc14': 'https://aka.ms/vc14/vc_redist.x64.exe',
    }
    package = deps.file(f'archives/runtimes/{runtime}-x64.exe', urls[runtime])
    signature_env = {**os.environ, 'LATTICE_RUNTIME_PACKAGE': str(package)}
    signature = "$s = Get-AuthenticodeSignature -LiteralPath $env:LATTICE_RUNTIME_PACKAGE; if ($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch 'Microsoft Corporation') { throw 'Invalid Microsoft runtime signature' }"
    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', signature], env=signature_env,
                   check=True, stdout=subprocess.DEVNULL, timeout=60)
    raw = deps.workspace() / 'runtimes' / runtime
    tool = resolved_path(sevenzip) if sevenzip else deps.existing('tools/7zip/7za.exe')
    if not tool:
        archive = deps.file('archives/7za920.zip', 'https://www.7-zip.org/a/7za920.zip',
                            '2a3afe19c180f8373fa02ff00254d5394fec0349f5804e0ad2f6067854ff28ac')
        tools = deps.workspace() / 'tools/7zip'
        extract(archive, tools)
        tool = tools / '7za.exe'
    extract_runtime_package(package, raw, tool)
    copy_dlls(raw)
    result = subprocess.run([str(executable(home, 'mysqld')), '--no-defaults', '--version'], env=env,
                            capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise ValueError(f'MySQL cannot start after runtime preparation (exit {result.returncode}); supply --runtime-dir')
    return env


def extract_runtime_package(package, destination, sevenzip=None):
    """Microsoft redistributables embed CABs; expand them without running an installer."""
    destination = Path(destination)
    tool = resolved_path(sevenzip) if sevenzip else None
    if tool and not tool.is_file():
        raise ValueError('--sevenzip must point to 7z.exe or 7za.exe')
    queue = [Path(package)]
    seen = set()
    index = 0
    while queue:
        item = queue.pop(0)
        digest = checksum(item)
        if digest in seen:
            continue
        seen.add(digest)
        if len(seen) > 32:
            raise ValueError('Too many nested runtime cabinets')
        data = item.read_bytes()
        if data[:4] != b'MSCF':
            offset = 0
            while True:
                offset = data.find(b'MSCF', offset)
                if offset < 0:
                    break
                if offset + 36 <= len(data):
                    size = struct.unpack_from('<I', data, offset + 8)[0]
                    if 36 <= size <= len(data) - offset:
                        cabinet = destination / 'cabinets' / f'{index}.cab'
                        cabinet.parent.mkdir(parents=True, exist_ok=True)
                        cabinet.write_bytes(data[offset:offset + size])
                        queue.append(cabinet)
                        index += 1
                offset += 4
            continue
        output = destination / 'expanded' / str(index)
        output.mkdir(parents=True, exist_ok=True)
        index += 1
        command = [str(tool), 'x', '-y', str(item), f'-o{output}'] if tool else [str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/expand.exe'), '-F:*', str(item), str(output)]
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, timeout=120)
        for path in output.rglob('*'):
            if path.is_file():
                with path.open('rb') as stream:
                    if stream.read(4) == b'MSCF':
                        queue.append(path)


@contextmanager
def mysql_fixture(deps, version='8.4.2', home=None, port=3306, logs=None,
                  runtime_dir=None, sevenzip=None):
    from test import require_free_port
    require_free_port(port)
    home = mysql_home(deps, version, home)
    env = runtime_environment(deps, home, version, runtime_dir, sevenzip)
    data = deps.workspace() / 'fixtures' / f'mysql-{version}-{uuid.uuid4().hex}'
    data.parent.mkdir(parents=True, exist_ok=True)
    log_dir = Path(logs) if logs else deps.workspace() / 'logs'
    log_dir.mkdir(parents=True, exist_ok=True)
    with (log_dir / f'mysql-{version}.log').open('wb') as log:
        base = [str(executable(home, 'mysqld')), '--no-defaults', f'--basedir={home}', f'--datadir={data}']
        if version.startswith(('5.5.', '5.6.')):
            shutil.copytree(home / 'data', data)  # Only the ZIP's cold seed, never fixture-data.
        else:
            data.mkdir()
            subprocess.run(base + ['--initialize-insecure', '--console'], env=env,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        options = [f'--port={port}', '--bind-address=127.0.0.1', '--max-connections=40', '--console']
        if not version.startswith('5.'):
            options += ['--mysqlx=0', '--default-time-zone=+00:00']
        process = subprocess.Popen(base + options, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        client = ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root', '--connect-timeout=5']
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'MySQL {version} exited; inspect {log_dir}')
                ready = subprocess.run([str(executable(home, 'mysqladmin')), *client, 'ping'], env=env,
                                       capture_output=True, timeout=10)
                if ready.returncode == 0:
                    break
                time.sleep(.5)
            else:
                raise TimeoutError(f'MySQL {version} not ready; inspect {log_dir}')
            actual = subprocess.check_output([str(executable(home, 'mysql')), *client, '--batch', '--skip-column-names',
                                              '--execute=SELECT @@datadir'], env=env, text=True, timeout=15).strip()
            if Path(actual).resolve() != data.resolve():
                raise RuntimeError('Unexpected data directory; refusing fixture setup')
            subprocess.run([str(executable(home, 'mysql')), *client, '--execute=CREATE DATABASE shop_db'],
                           env=env, check=True, timeout=15)
            print(f'MySQL {version}: owned PID {process.pid}, data {data}', flush=True)
            yield
        finally:
            if process.poll() is None:
                try:
                    subprocess.run([str(executable(home, 'mysqladmin')), *client, 'shutdown'], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                    process.wait(timeout=20)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait(timeout=20)
