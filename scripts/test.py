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
        listener.bind(("127.0.0.1", port))


@contextmanager
def hsqldb_fixture():
    require_free_port(9001)
    output = ROOT / "build/fixtures"
    output.mkdir(parents=True, exist_ok=True)
    with (output / "hsqldb.log").open("wb") as log:
        process = subprocess.Popen([java_command(), "-cp", str(ROOT / "lib/hsqldb-2.7.3.jar"),
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
    created = False
    try:
        subprocess.run(["docker", "run", "--detach", "--rm", "--name", name,
                        "--publish", "127.0.0.1:3306:3306", "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                        "--env", "MYSQL_ROOT_HOST=%", "--env", "MYSQL_DATABASE=shop_db",
                        "mysql:8.4"], check=True)
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
        if created:
            output = ROOT / "build/fixtures"
            output.mkdir(parents=True, exist_ok=True)
            with (output / "mysql.log").open("wb") as log:
                subprocess.run(["docker", "logs", name], stdout=log, stderr=subprocess.STDOUT)
            subprocess.run(["docker", "stop", "--time", "15", name], check=True)


def command(args):
    if os.name == "nt" and any(character in str(ROOT) for character in '\"&|<>^%!\r\n'):
        raise ValueError("Windows checkout paths must not contain shell metacharacters")
    tasks = ["test"]
    if args.live:
        tasks += ["integrationTest", "fallbackDriverTest"]
    if args.build:
        tasks += ["buildPlugin"]
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
    parser.add_argument("--ide-home", type=Path, help="Optional local IntelliJ 2025.1 SDK; otherwise Gradle downloads it")
    parser.add_argument("--version")
    parser.add_argument("--notes-file", type=Path)
    args = parser.parse_args()
    if (args.hsqldb or args.mysql) and not args.live:
        parser.error("Fixture options require --live")
    launch = command(args)
    with ExitStack() as fixtures:
        if args.mysql:
            fixtures.enter_context(mysql_fixture())
        if args.hsqldb:
            fixtures.enter_context(hsqldb_fixture())
        subprocess.run(launch, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
