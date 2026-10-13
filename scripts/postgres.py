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
        subprocess.run([docker, 'run', '--detach', '--rm', '--name', name, '--publish',
                        f'127.0.0.1:{port}:5432', '--env', f'POSTGRES_PASSWORD={TEST_PASSWORD}',
                        image], check=True)
        created = True
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = subprocess.run([docker, 'exec', name, 'psql', '-U', 'postgres', '-d', 'postgres',
                                     '-tAc', 'SELECT 1'], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, check=False)
            if result.returncode == 0:
                break
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
            subprocess.run([docker, 'rm', '--force', name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        if cleanup and not existed:
            remove_downloaded_docker_image(image, docker)
