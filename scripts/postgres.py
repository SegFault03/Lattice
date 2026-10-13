"""Disposable, loopback-only PostgreSQL fixtures shared by integration and matrix runners."""
from contextlib import contextmanager
from pathlib import Path
import time
import uuid
import processes as subprocess
from common import ROOT

TEST_PASSWORD = 'lattice-test'

@contextmanager
def postgres_fixture(port, version='18.6', docker='docker', cleanup=True, image=None):
    from test import require_free_port, remove_downloaded_docker_image
    require_free_port(port)
    image = image or f'postgres:{version}'
    name = 'lattice-postgres-' + uuid.uuid4().hex
    existed = subprocess.run([docker, 'image', 'inspect', image], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, check=False).returncode == 0
    created = False
    try:
        subprocess.run([docker, 'run', '--detach', '--name', name, '--publish',
                        f'127.0.0.1:{port}:5432', '--env', f'POSTGRES_PASSWORD={TEST_PASSWORD}',
                        image], check=True)
        created = True
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = subprocess.run([docker, 'exec', '--env', f'PGPASSWORD={TEST_PASSWORD}', name, 'psql', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'postgres',
                                     '-tAc', 'SELECT 1'], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, check=False)
            if result.returncode == 0:
                break
            state = subprocess.run([docker, 'inspect', '--format', '{{.State.Running}}', name],
                                   capture_output=True, text=True, check=False)
            if state.returncode != 0 or state.stdout.strip() != 'true':
                raise RuntimeError(f'PostgreSQL {version} fixture stopped before becoming ready')
            time.sleep(0.5)
        else:
            raise TimeoutError('PostgreSQL fixture did not become ready')
        yield name
    finally:
        if created:
            logs = ROOT / 'build/fixtures'
            logs.mkdir(parents=True, exist_ok=True)
            with (logs / f'postgres-{version}.log').open('wb') as output:
                subprocess.run([docker, 'logs', name], stdout=output, stderr=subprocess.STDOUT, check=False)
            subprocess.run([docker, 'stop', '--timeout', '15', name], check=False)
            subprocess.run([docker, 'rm', '--force', '--volumes', name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        if cleanup and not existed:
            remove_downloaded_docker_image(image, docker)

# Vendor SHA-256 files: https://ftp.postgresql.org/pub/source/v<VERSION>/
LEGACY_SOURCES = {
    '9.2.24': 'a754c02f7051c2f21e52f8669a421b50485afcde9a581674d6106326b189d126',
    '10.23': '94a4b2528372458e5662c18d406629266667c437198160a18cdfd2c4a4d6eee9',
    '11.22': '2cb7c97d7a0d7278851bbc9c61f467b69c094c72b81740b751108e7892ebe1f0',
    '8.4.22': '5c1d56ce77448706d9dd03b2896af19d9ab1b9b8dcdb96c39707c74675ca3826',
    '9.0.23': '3dbcbe19c814139a3f4be8bc6b49db804753cbc49979f345083e835c52b4d7de',
}
POSTGRES_VERSIONS = ('8.4.22', '9.0.23', '9.1.24', '9.2.24', '9.3.25', '9.4.26',
                     '9.5.25', '9.6.24', '10.23', '11.22', '12.22', '13.23', '14.24',
                     '15.19', '16.15', '17.11', '18.6')

def matrix_drivers(version):
    return ('42.2.27', '42.7.3' if version in ('8.4.22', '9.0.23') else '42.7.14')

@contextmanager
def matrix_fixture(port, version, deps, docker='docker', cleanup=True, host_compiler=False):
    """Source fixtures are owned separately so readiness failures still remove built images."""
    if version not in LEGACY_SOURCES:
        with postgres_fixture(port, version, docker, cleanup) as name:
            yield name
        return
    import shutil
    from dependencies import download
    from test import remove_downloaded_docker_image
    image = f'lattice-postgres-source:{version}'
    existed = subprocess.run([docker, 'image', 'inspect', image], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, check=False).returncode == 0
    try:
        if not existed:
            context = deps.workspace() / f'postgres-source-{version}'
            context.mkdir(parents=True, exist_ok=True)
            for file in ('Dockerfile', 'entrypoint.sh'):
                shutil.copy2(ROOT / 'scripts/fixtures/postgres' / file, context / file)
            archive = context / 'source.tar.bz2'
            download(f'https://ftp.postgresql.org/pub/source/v{version}/postgresql-{version}.tar.bz2',
                     archive, LEGACY_SOURCES[version])
            if host_compiler:
                import os
                import platform
                compiler = subprocess.run(['gcc', '-dumpversion'], capture_output=True, text=True, check=True)
                libc, libc_version = platform.libc_ver()
                if int(compiler.stdout.strip().split('.')[0]) < 14 or libc != 'glibc' or tuple(map(int, libc_version.split('.'))) > (2, 41):
                    raise RuntimeError('--host-compiler needs GCC 14+ and glibc <= 2.41; use the default container build on this host')
                from dependencies import extract
                source = context / 'source'
                extract(archive, source)
                source = source / f'postgresql-{version}'
                env = {**os.environ, 'CFLAGS': '-O0 -fcommon -Wno-error=incompatible-pointer-types -Wno-error=implicit-function-declaration -Wno-error=implicit-int -Wno-error=return-mismatch'}
                subprocess.run(['./configure', '--prefix=/opt/pgsql', '--without-readline', '--without-zlib'], cwd=source, env=env, check=True)
                subprocess.run(['make', '-j4'], cwd=source, env=env, check=True)
                subprocess.run(['make', 'install', f'DESTDIR={context / "install"}'], cwd=source, env=env, check=True)
                shutil.copy2(ROOT / 'scripts/fixtures/postgres/Dockerfile.host', context / 'Dockerfile')
            subprocess.run([docker, 'build', '--build-arg', f'VERSION={version}', '--tag', image, str(context)], check=True)
        with postgres_fixture(port, version, docker, False, image) as name:
            yield name
    finally:
        if cleanup and not existed:
            remove_downloaded_docker_image(image, docker)
