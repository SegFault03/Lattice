#!/usr/bin/env python3
"""Run Gradle checks with optional owned, disposable database fixtures."""
import argparse
from contextlib import ExitStack, contextmanager
import os
from pathlib import Path
import socket
import subprocess
import time
import uuid

from common import ROOT, gradle_command, java_command
from release import validate_version


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
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))


def remove_downloaded_docker_image(image):
    last_error = ""
    for attempt in range(10):
        result = subprocess.run(["docker", "image", "rm", image], capture_output=True,
                                text=True, check=False)
        if result.returncode == 0 or subprocess.run(
                ["docker", "image", "inspect", image], stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, check=False).returncode != 0:
            return
        last_error = result.stderr.strip()
        if attempt < 9:
            time.sleep(1)
    raise RuntimeError(f"Could not remove downloaded Docker image {image}: {last_error}")


@contextmanager
def hsqldb_fixture():
    require_free_port(9001)
    output = ROOT / "build/fixtures"
    output.mkdir(parents=True, exist_ok=True)
    with (output / "hsqldb.log").open("wb") as log:
        process = subprocess.Popen([java_command(), "-cp", str(ROOT / "lib/hsqldb-2.7.4.jar"),
                                    "org.hsqldb.server.Server", "--address", "127.0.0.1",
                                    "--database.0", "mem:testdb", "--dbname.0", "testdb",
                                    "--port", "9001", "--silent", "true"], cwd=ROOT,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_port(9001, lambda: process.poll() is None)
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
def mysql_fixture():
    require_free_port(3306)
    name = "lattice-test-" + uuid.uuid4().hex
    image = "mysql:8.4"
    image_preexisting = subprocess.run(["docker", "image", "inspect", image],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       check=False).returncode == 0
    created = False
    try:
        subprocess.run(["docker", "run", "--detach", "--rm", "--name", name,
                        "--publish", "127.0.0.1:3306:3306", "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                        "--env", "MYSQL_ROOT_HOST=%", "--env", "MYSQL_DATABASE=shop_db",
                        image], check=True)
        created = True
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
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
                    subprocess.run(["docker", "logs", name], stdout=log, stderr=subprocess.STDOUT)
                subprocess.run(["docker", "stop", "--timeout", "15", name], check=True)
                # --rm normally handles this, but explicitly wait for our container reference
                # to disappear before trying to release a newly pulled image.
                subprocess.run(["docker", "rm", "--force", name], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finally:
            if not image_preexisting:
                remove_downloaded_docker_image(image)


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
    parser.add_argument("--mysql", action="store_true", help="Own a MySQL 8.4 Docker fixture for this run")
    parser.add_argument("--build", action="store_true", help="Also package the plugin ZIP")
    parser.add_argument("--integration-only", action="store_true",
                        help="With --live, run only the integration and fallback-driver suites")
    parser.add_argument("--ide-home", type=Path, help="Optional local IntelliJ 2025.1 SDK; otherwise Gradle downloads it")
    parser.add_argument("--version")
    parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    if (args.hsqldb or args.mysql) and not args.live:
        parser.error("Fixture options require --live")
    if args.integration_only and not args.live:
        parser.error("--integration-only requires --live")
    if args.integration_only and args.build:
        parser.error("--integration-only cannot be combined with --build")
    launch = command(args)
    with ExitStack() as fixtures:
        if args.mysql:
            fixtures.enter_context(mysql_fixture())
        if args.hsqldb:
            fixtures.enter_context(hsqldb_fixture())
        subprocess.run(launch, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
