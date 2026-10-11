#!/usr/bin/env python3
"""Run Gradle checks with optional owned, disposable database fixtures."""
import argparse
from contextlib import ExitStack, contextmanager
import os
from pathlib import Path
import re
import socket
import processes as subprocess
import time
import uuid

from common import ROOT, gradle_command, java_command
from release import validate_version
from dependencies import Dependencies, add_dependency_options


def wait_port(port, alive, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not alive():
            raise RuntimeError(f"Fixture on port {port} exited; inspect build/fixtures")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(0.5)
    raise TimeoutError(f"Fixture on port {port} did not become ready")


def require_free_port(port):
    with socket.socket() as listener:
        if os.name == "nt":
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        listener.bind(("127.0.0.1", port))


def port_number(value):
    port = int(value)
    if not 0 <= port <= 65535:
        raise argparse.ArgumentTypeError('Port must be between 0 and 65535')
    return port


def fixture_port(requested, default, owned=True):
    """Choose an isolated ephemeral port, or validate the caller's explicit port."""
    if not owned:
        if requested == 0:
            raise ValueError('Port 0 requires an owned --mysql/--hsqldb fixture')
        return default if requested is None else requested
    if requested:
        require_free_port(requested)
        return requested
    with socket.socket() as listener:
        if os.name == 'nt':
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def remove_downloaded_docker_image(image, docker="docker"):
    last_error = ""
    for attempt in range(10):
        result = subprocess.run([docker, "image", "rm", image], capture_output=True,
                                text=True, check=False)
        if result.returncode == 0 or subprocess.run(
                [docker, "image", "inspect", image], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, check=False).returncode != 0:
            return
        last_error = result.stderr.strip()
        if attempt < 9:
            time.sleep(1)
    raise RuntimeError(f"Could not remove downloaded Docker image {image}: {last_error}")


@contextmanager
def hsqldb_fixture(java_home=None, jar=None, port=9001):
    require_free_port(port)
    jar = (jar or ROOT / 'build/fallback-drivers/hsqldb-2.7.4.jar').expanduser().resolve()
    if not jar.is_file():
        raise ValueError('HSQLDB fixture JAR does not exist: ' + str(jar))
    output = ROOT / "build/fixtures"
    output.mkdir(parents=True, exist_ok=True)
    with (output / "hsqldb.log").open("wb") as log:
        process = subprocess.Popen([java_command(java_home), "-cp", str(jar),
                                    "org.hsqldb.server.Server", "--address", "127.0.0.1",
                                    "--database.0", "mem:testdb", "--dbname.0", "testdb",
                                    "--port", str(port), "--silent", "true"], cwd=ROOT,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_port(port, lambda: process.poll() is None)
            yield
        finally:
            if process.poll() is None:
                process.terminate()  # Disposable memory database; no files to checkpoint.
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


@contextmanager
def mysql_fixture(cleanup=True, docker="docker", port=3306):
    require_free_port(port)
    name = "lattice-test-" + uuid.uuid4().hex
    image = "mysql:8.4"
    image_preexisting = subprocess.run([docker, "image", "inspect", image],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       check=False).returncode == 0
    created = False
    try:
        subprocess.run([docker, "run", "--detach", "--rm", "--name", name,
                        "--publish", f"127.0.0.1:{port}:3306", "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                        "--env", "MYSQL_ROOT_HOST=%", "--env", "MYSQL_DATABASE=shop_db",
                        image], check=True)
        created = True
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            ready = subprocess.run([docker, "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise TimeoutError("MySQL fixture did not become ready")
        yield
    finally:
        try:
            if created:
                output = ROOT / "build/fixtures"
                output.mkdir(parents=True, exist_ok=True)
                with (output / "mysql.log").open("wb") as log:
                    subprocess.run([docker, "logs", name], stdout=log, stderr=subprocess.STDOUT)
                subprocess.run([docker, "stop", "--timeout", "15", name], check=True)
                # --rm normally handles this, but explicitly wait for our container reference
                # to disappear before trying to release a newly pulled image.
                subprocess.run([docker, "rm", "--force", name], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            if cleanup and not image_preexisting:
                remove_downloaded_docker_image(image, docker)


def validate_java_home(home):
    home = Path(home).expanduser().resolve()
    java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    release = home / "release"
    try:
        version = release.read_text(encoding="utf-8")
    except OSError:
        version = ""
    if java.is_file() and re.search(r'^JAVA_VERSION="21(?:\.|\")', version, re.M):
        return home
    raise ValueError("--java-home must point to Java 21")


def gradle_environment(java_home=None):
    if java_home is None:
        return None
    return {**os.environ, "JAVA_HOME": str(validate_java_home(java_home))}


def command(args):
    if os.name == "nt" and any(character in str(ROOT) for character in '\"&|<>^%!\r\n'):
        raise ValueError("Windows checkout paths must not contain shell metacharacters")
    integration_only = getattr(args, "integration_only", False)
    tasks = [] if integration_only else ["test"]
    if args.live:
        tasks += ["integrationTest", "fallbackDriverTest"]
    if args.build:
        tasks += ["buildPlugin"]
    if not tasks:
        raise ValueError("No Gradle tasks selected")
    options = []
    if args.version:
        options.append("-PreleaseVersion=" + validate_version(args.version))
    for property_name, value in (("lattice.ide.home", args.ide_home), ("releaseNotesFile", args.notes_file)):
        if value:
            value = str(value.expanduser().resolve())
            # These characters have special meanings in the Windows batch shell.
            if os.name == "nt" and any(character in value for character in '\"&|<>^%!\r\n'):
                raise ValueError("Windows Gradle paths must not contain shell metacharacters")
            options.append(f"-P{property_name}={value}")
    return gradle_command() + ["--no-daemon"] + tasks + options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Run integration and fallback-driver checks")
    parser.add_argument("--hsqldb", action="store_true", help="Own an in-memory HSQLDB fixture for this run")
    parser.add_argument("--mysql", action="store_true", help="Own a MySQL fixture (native 8.4.2 on Windows; Docker 8.4 on Linux/macOS)")
    parser.add_argument("--build", action="store_true", help="Also package the plugin ZIP")
    parser.add_argument("--integration-only", action="store_true",
                        help="With --live, run only the integration and fallback-driver suites")
    parser.add_argument("--ide-home", type=Path, help="Optional local IntelliJ 2025.1 SDK; otherwise Gradle downloads it")
    parser.add_argument("--java-home", type=Path, help="JDK 21 directory; otherwise use JAVA_HOME or PATH")
    parser.add_argument("--version")
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument('--mysql-home', type=Path, help='Native Windows MySQL 8.4.2 home or mysqld.exe; otherwise download missing fixture')
    parser.add_argument('--docker', help='Docker CLI path; selects Docker fixtures on Windows')
    parser.add_argument('--runtime-dir', type=Path, help='Windows VC runtime DLL directory')
    parser.add_argument('--sevenzip', type=Path, help='7z.exe/7za.exe for missing Windows runtime extraction')
    parser.add_argument('--hsqldb-jar', type=Path, help='HSQLDB fixture JAR; otherwise find/download HSQLDB 2.7.4')
    parser.add_argument('--mysql-port', type=port_number, help='Owned fixture port (default: automatic); without --mysql use external port 3306')
    parser.add_argument('--hsqldb-port', type=port_number, help='Owned fixture port (default: automatic); without --hsqldb use external port 9001')
    add_dependency_options(parser, cleanup=True)
    args = parser.parse_args()
    if (args.hsqldb or args.mysql) and not args.live:
        parser.error("Fixture options require --live")
    if args.integration_only and not args.live:
        parser.error("--integration-only requires --live")
    if args.integration_only and args.build:
        parser.error("--integration-only cannot be combined with --build")
    with Dependencies(args) as deps:
        args.java_home = deps.java(args.java_home)
        if args.ide_home is None and args.binaries_dir:
            args.ide_home = deps.ide()
        launch = command(args)
        environment = deps.environment(args.java_home)
        mysql_port = fixture_port(args.mysql_port, 3306, args.mysql)
        hsql_port = fixture_port(args.hsqldb_port, 9001, args.hsqldb)
        environment.update(LATTICE_TEST_MYSQL_PORT=str(mysql_port), LATTICE_TEST_HSQLDB_PORT=str(hsql_port))
        if args.live:
            print(f'Live test ports: MySQL {mysql_port}, HSQLDB {hsql_port}', flush=True)
        with ExitStack() as fixtures:
            if args.mysql:
                if os.name == 'nt' and not args.docker or args.mysql_home:
                    from windows.native_mysql import mysql_fixture as native_fixture
                    fixtures.enter_context(native_fixture(deps, home=args.mysql_home, logs=ROOT / 'build/fixtures',
                                                          runtime_dir=args.runtime_dir, sevenzip=args.sevenzip, port=mysql_port))
                else:
                    fixtures.enter_context(mysql_fixture(args.cleanup, args.docker or 'docker', port=mysql_port))
            if args.hsqldb:
                jar = deps.jdbc('hsqldb', args.hsqldb_jar)
                fixtures.enter_context(hsqldb_fixture(args.java_home, jar, port=hsql_port))
            subprocess.run(launch, cwd=ROOT, env=environment, check=True)


if __name__ == "__main__":
    main()
