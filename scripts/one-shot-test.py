#!/usr/bin/env python3
"""Run Lattice's shared CI/local suites in disposable tool/cache directories.

The runner checks tooling, Java unit tests, package integrity, live database behavior,
all supported IntelliJ targets, and the documented Java 8/JDBC compatibility matrix.
Use --windows-ui for the complete Windows scope, including real IDE validation.
CI executes individual catalog entries with --suite ID --in-place.
Downloaded assets are removed by default; --no-cleanup retains them for reuse.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import re
import shutil
import shlex
import socket
import processes as subprocess
import sys
import tarfile
import threading
import time
import urllib.parse
import urllib.request
import uuid

from common import gradle_command, resolved_path
from dependencies import Dependencies, add_dependency_options, executable
from test import fixture_port, port_number
import test_suites


ROOT = Path(__file__).resolve().parents[1]
SUITE_CATALOG = test_suites.load_catalog()
IDE_VERSIONS = tuple(s['version'] for s in SUITE_CATALOG['suites'] if s['kind'] == 'verifier')
MYSQL_MATRIX = tuple((server, tuple(drivers)) for server, drivers in SUITE_CATALOG['mysql_matrix'])
HSQL_VERSIONS = tuple(SUITE_CATALOG['hsqldb_versions'])
WINDOWS_UI_SCENARIOS = tuple((s['id'].removeprefix('ui-'), s['theme'], s['env'])
                             for s in test_suites.select_suites('windows', phases={'ui'}) if s['kind'] == 'ui')
TOTAL_STEPS = 8


class RunnerError(RuntimeError):
    pass


class CommandFailed(RunnerError):
    pass


class Progress:
    """Small step counter that remains readable with or without ANSI support."""

    def __init__(self, total: int = TOTAL_STEPS):
        self.total = total
        self.current = 0
        self.active = "preflight"
        self.use_color = sys.stdout.isatty() and "NO_COLOR" not in os.environ

    def _paint(self, text: str, color: str) -> str:
        return f"\033[{color}m{text}\033[0m" if self.use_color else text

    @contextmanager
    def step(self, title: str):
        self.current += 1
        self.active = title
        filled = int(self.current * 12 / self.total)
        bar = "=" * filled + "-" * (12 - filled)
        print(f"\n{self._paint('[' + bar + ']', '36')} {self.current:02}/{self.total}  {title}", flush=True)
        started = time.monotonic()
        try:
            yield
        except BaseException:
            elapsed = time.monotonic() - started
            print(self._paint(f"  FAIL  {title} ({elapsed:.1f}s)", "31"), flush=True)
            raise
        else:
            elapsed = time.monotonic() - started
            print(self._paint(f"  PASS  {title} ({elapsed:.1f}s)", "32"), flush=True)


class CommandRunner:
    def __init__(self, logs: Path, env: dict[str, str], progress: Progress):
        self.logs = logs
        self.env = env
        self.progress = progress
        self.logs.mkdir(parents=True, exist_ok=True)
        self.index = 0
        self.last_log: Path | None = None

    def run(self, command: list[str], *, cwd: Path, name: str,
            timeout: int = 600, check: bool = True,
            env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        self.index += 1
        safe_name = re.sub(r"[^A-Za-z0-9_.-]+", "-", name).strip("-") or "command"
        log = self.logs / f"{self.index:03d}-{safe_name}.log"
        self.last_log = log
        merged_env = dict(self.env)
        if env:
            merged_env.update(env)
        display = shlex.join(command)
        print(f"  $ {display}", flush=True)
        started = time.monotonic()
        timed_out = threading.Event()
        try:
            with log.open("w", encoding="utf-8", errors="replace") as output:
                output.write(f"cwd: {cwd}\ncommand: {display}\n\n")
                process = subprocess.Popen(command, cwd=cwd, env=merged_env,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           text=True, encoding="utf-8", errors="replace", bufsize=1,
                                           **({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}))

                def terminate_owned_tree():
                    if process.poll() is None:
                        process.kill()

                def terminate_if_late():
                    if process.poll() is None:
                        timed_out.set()
                        terminate_owned_tree()

                timer = threading.Timer(timeout, terminate_if_late)
                timer.daemon = True
                timer.start()
                try:
                    assert process.stdout is not None
                    with process.stdout:
                        for line in process.stdout:
                            output.write(line)
                            output.flush()
                            print(line, end="", flush=True)
                    returncode = process.wait()
                except BaseException:
                    terminate_owned_tree()
                    process.wait(timeout=30)
                    raise
                finally:
                    timer.cancel()
        except OSError as failure:
            with log.open("a", encoding="utf-8") as output:
                output.write(f"\nCould not start command: {failure}\n")
            raise CommandFailed(f"Could not start {display}: {failure} (log: {log})") from failure
        elapsed = time.monotonic() - started
        if timed_out.is_set():
            raise CommandFailed(f"Command timed out after {timeout}s: {display} (log: {log})")
        if check and returncode != 0:
            raise CommandFailed(f"Command exited {returncode}: {display} (log: {log})")
        return subprocess.CompletedProcess(command, returncode, "", "")


def check_linux() -> None:
    if not (sys.platform.startswith("linux") or sys.platform == "win32"):
        raise RunnerError("one-shot-test.py supports Linux and Windows only")
    if sys.version_info < (3, 11):
        raise RunnerError("Python 3.11 or newer is required")


def require_free_port(port: int) -> None:
    with socket.socket() as listener:
        # Unix permits reuse after shutdown; Windows requires exclusive ownership.
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE if os.name == 'nt' else socket.SO_REUSEADDR, 1)
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as failure:
            raise RunnerError(f"Port {port} must be free (127.0.0.1): {failure}") from failure


def java_major(java: Path) -> int:
    result = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=False)
    text = result.stdout + result.stderr
    match = re.search(r'version "(?:1\.)?(\d+)', text)
    if result.returncode != 0 or not match:
        raise RunnerError(f"Cannot determine Java version from {java}: {text.strip()}")
    return int(match.group(1))


def extract_tar(archive: Path, destination: Path) -> None:
    from dependencies import extract
    try:
        extract(archive, destination)
    except tarfile.FilterError as failure:
        raise RunnerError(f'Runtime archive escapes tool directory: {failure}') from failure


def prepare_proxy_environment(env: dict[str, str], java_home: Path, tools: Path) -> dict[str, str]:
    result = dict(env)
    options = result.get("JAVA_TOOL_OPTIONS", "")
    proxy_url = result.get("HTTPS_PROXY") or result.get("https_proxy")
    if proxy_url:
        proxy = urllib.parse.urlparse(proxy_url)
        if proxy.hostname and proxy.port:
            options += (f" -Dhttps.proxyHost={proxy.hostname} -Dhttps.proxyPort={proxy.port}"
                        f" -Dhttp.proxyHost={proxy.hostname} -Dhttp.proxyPort={proxy.port}"
                        " -Dhttp.nonProxyHosts=localhost|127.*|[::1]")
    cert = result.get("CODEX_PROXY_CERT")
    if cert and Path(cert).is_file():
        source = java_home / "lib/security/cacerts"
        if source.is_file():
            tools.mkdir(parents=True, exist_ok=True)
            truststore = tools / "proxy-truststore"
            shutil.copy2(source, truststore)
            keytool = executable(java_home, "keytool")
            subprocess.run([str(keytool), "-importcert", "-noprompt", "-alias", "lattice-one-shot-proxy",
                            "-file", cert, "-keystore", str(truststore), "-storepass", "changeit"],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            options += f' -Djavax.net.ssl.trustStore="{truststore}" -Djavax.net.ssl.trustStorePassword=changeit'
    result["JAVA_TOOL_OPTIONS"] = options.strip()
    return result


def copy_source(destination: Path) -> Path:
    # A caller may place downloads inside the checkout. Exclude that subtree
    # before creating the copy so it cannot recursively copy its own output.
    nested = destination.resolve().relative_to(ROOT.resolve()).parts[0] if destination.resolve().is_relative_to(ROOT.resolve()) else None
    def ignore(_directory: str, names: list[str]) -> set[str]:
        return {name for name in names if name == nested or name.startswith("lattice-run-") or name in {
            ".git", ".gradle", ".intellijPlatform", "allure-results", ".haze", "local", "build", "out", ".idea", ".venv", "venv", "__pycache__", ".pytest_cache",
        }}
    shutil.copytree(ROOT, destination, ignore=ignore, symlinks=os.name != 'nt')
    return destination


def project_version(source: Path) -> str:
    properties = source / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("pluginVersion="):
            return line.split("=", 1)[1].strip()
    raise RunnerError("gradle.properties has no pluginVersion")


def run_gradle(runner: CommandRunner, source: Path, *tasks: str, properties: list[str] | None = None,
               name: str, timeout: int = 900) -> None:
    command = gradle_command(source) + ["--no-daemon", "--console=plain", "--stacktrace", *tasks]
    if os.name == 'nt' and any(any(c in arg for c in '\"&|<>^%!\r\n') for arg in [str(source), *(properties or [])]):
        raise RunnerError('Windows Gradle paths must not contain shell metacharacters')
    command.extend(properties or [])
    if runner.env.get('LATTICE_BUILD_IDE'):
        sdk = runner.env['LATTICE_BUILD_IDE']
        if os.name == 'nt' and any(c in sdk for c in '\"&|<>^%!\r\n'):
            raise RunnerError('Windows SDK paths must not contain shell metacharacters')
        command.append('-Plattice.ide.home=' + sdk)
    runner.run(command, cwd=source, name=name, timeout=timeout)


def run_plugin_verifier(runner: CommandRunner, source: Path, env: dict[str, str],
                        temp: Path, archive: Path, java_home: Path, version: str, deps=None, args=None) -> None:
    cache = temp / "verifier-cache"
    command = [sys.executable, str(source / "scripts/verify-plugin.py"), str(archive),
               "--ide-version", version, "--java-home", str(java_home),
               "--cache", str(cache), "--reports", str(source / "build/compatibility/verifier")]
    if deps is not None:
        sdk = deps.ide((args.ide_homes or {}).get(version) or (args.ide_home if version == '2025.1' else None), version)
        command += ['--ide-home', str(sdk)]
        if args.verifier_jar:
            command += ['--verifier-jar', str(args.verifier_jar)]
        elif deps.existing('tools/verifier/verifier-cli-1.410-all.jar'):
            command += ['--verifier-jar', str(deps.existing('tools/verifier/verifier-cli-1.410-all.jar'))]
    runner.run(command, cwd=source, env=env, name=f"plugin-verifier-{version}", timeout=900)




def docker_image_exists(image: str, docker=None) -> bool:
    return subprocess.run([docker or "docker", "image", "inspect", image], stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, check=False).returncode == 0


def remove_downloaded_image(image: str, docker=None) -> None:
    last_error = ""
    for attempt in range(10):
        result = subprocess.run([docker or "docker", "image", "rm", image], capture_output=True,
                                text=True, check=False)
        if result.returncode == 0 or not docker_image_exists(image, docker):
            return
        last_error = result.stderr.strip()
        if attempt < 9:
            time.sleep(1)
    raise RunnerError(f"Could not remove downloaded Docker image {image}: {last_error}")


@contextmanager
def mysql_server(runner: CommandRunner, image_tag: str, port: int = 3306, cleanup=True):
    docker = runner.env.get("LATTICE_DOCKER", "docker")
    image = f"mysql:{image_tag}"
    preexisting = docker_image_exists(image, docker)
    name = "lattice-one-shot-" + uuid.uuid4().hex[:12]
    created = False
    log_path = runner.logs / f"mysql-{image_tag}.container.log"
    try:
        runner.run([docker or "docker", "run", "--detach", "--name", name,
                    "--publish", f"127.0.0.1:{port}:3306", "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                    "--env", "MYSQL_ROOT_HOST=%", "--env", "MYSQL_DATABASE=shop_db", image],
                   cwd=ROOT, name=f"docker-run-mysql-{image_tag}", timeout=600)
        created = True
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            ping = subprocess.run([docker or "docker", "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if ping.returncode == 0:
                print(f"  MySQL {image_tag} is accepting connections on 127.0.0.1:{port}", flush=True)
                break
            state = subprocess.run([docker or "docker", "inspect", "--format", "{{.State.Running}} {{.State.ExitCode}}", name],
                                   capture_output=True, text=True, check=False)
            if state.returncode != 0 or state.stdout.strip().startswith("false"):
                raise RunnerError(f"MySQL {image_tag} fixture stopped before becoming ready ({state.stdout.strip() or 'container unavailable'}); see {log_path}")
            time.sleep(1)
        else:
            raise RunnerError(f"MySQL {image_tag} did not become ready within 180 seconds")
        yield
    finally:
        container_exists = subprocess.run([docker or "docker", "container", "inspect", name],
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                          check=False).returncode == 0
        if created or container_exists:
            with log_path.open("w", encoding="utf-8") as output:
                subprocess.run([docker or "docker", "logs", name], stdout=output, stderr=subprocess.STDOUT, check=False)
            subprocess.run([docker or "docker", "stop", "--timeout", "15", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
            subprocess.run([docker or "docker", "rm", "--force", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        if cleanup and not preexisting:
            remove_downloaded_image(image, docker)
            print(f"  Removed downloaded Docker image {image}", flush=True)


def prepare_java8_probe(source: Path, java_home: Path, probe_classes: Path,
                        runner: CommandRunner, env: dict[str, str]) -> None:
    source_file = source / "src/test/java/com/segfault03/ideadb/Java8DriverProbe.java"
    probe_classes.mkdir(parents=True, exist_ok=True)
    runner.run([str(executable(java_home, "javac")), "--release", "8", "-encoding", "UTF-8",
                "-d", str(probe_classes), str(source_file)], cwd=source,
               name="compile-java8-probe", timeout=120, env=env)


def preserve_failure(root: Path, temp: Path, logs: Path,
                     active: str, error: BaseException) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    destination = root / "build/one-shot-test" / f"failure-{stamp}"
    destination.mkdir(parents=True, exist_ok=True)
    if logs.exists():
        shutil.copytree(logs, destination / "logs", dirs_exist_ok=True)
    (destination / "failure.txt").write_text(
        f"Failed step: {active}\nFailure: {type(error).__name__}: {error}\n"
        f"Saved command logs: {destination / 'logs'}\n"
        f"Temporary workspace (cleaned): {temp}\n",
        encoding="utf-8",
    )
    return destination


def selected_suites(args: argparse.Namespace) -> list[dict]:
    if getattr(args, 'ci_group', None):
        host = getattr(args, 'platform', None) or test_suites.host_platform()
        suites = [s for s in test_suites.select_suites(host)
                  if s['group'] == args.ci_group and host in s['ci_hosts']]
        if not suites:
            raise ValueError(f'No CI suites registered for group {args.ci_group} on {host}')
        return suites
    phases = {"base"}
    if not args.skip_compatibility:
        phases |= {"ide", "database"}
    if args.compatibility_only:
        phases.discard("base")
    if args.database_compatibility_only:
        phases = {"database"}
    if getattr(args, "windows_ui", False):
        phases.add("ui")
    return test_suites.select_suites(getattr(args, "platform", None) or test_suites.host_platform(),
                                     phases=phases, ids=getattr(args, "suites", None))


def validation_plan(args: argparse.Namespace) -> tuple[bool, bool, bool, int]:
    suites = selected_suites(args)
    phases = {suite["phase"] for suite in suites}
    build_for_verifier = "ide" in phases and not any(s["kind"] == "build" for s in suites) and not getattr(args, "archive", None)
    return ("base" in phases, "ide" in phases, "database" in phases,
            2 + len(suites) + int(build_for_verifier))


def path_mapping(values, versions):
    result = {}
    for value in values:
        version, separator, path = value.partition('=')
        if not separator or version not in versions or not path or version in result:
            raise ValueError('Expected a unique supported VERSION=PATH: ' + value)
        result[version] = resolved_path(path)
    return result


def build_archive(context, tasks, package_tasks=None):
    source, runner = context.source, context.runner
    package_tasks = package_tasks or test_suites.gradle_tasks("functional-build", packaging=True)
    run_gradle(runner, source, *tasks, *package_tasks, name="gradle-plugin-package", timeout=1200)
    version = project_version(source)
    archive = source / "build/distributions" / f"Lattice-{version}.zip"
    if not archive.is_file():
        raise RunnerError(f"Gradle completed without producing {archive}")
    runner.run([sys.executable, "scripts/check-release-archive.py", str(archive), "--version", version],
               cwd=source, name="plugin-archive-check", timeout=300)
    context.archive = archive


def run_ui_suite(suite, context):
    args, source, runner = context.args, context.source, context.runner
    ide = context.deps.ide(args.ide_home)
    name = suite["id"].removeprefix("ui-")
    output = (ROOT / "build/ui-review/windows-ci" / context.stamp / name if os.name == "nt"
              else source / "build/ui-review" / name)
    environment = dict(context.env)
    environment.update({
        "LATTICE_UI_REVIEW_OUTPUT": str(output), "LATTICE_UI_SCALE": "1.0",
        "LATTICE_UI_ALTER_TABLE_ONLY": "false", "LATTICE_UI_CREATE_TABLE_ONLY": "false",
        "LATTICE_UI_CELL_DETAILS_ONLY": "false", "LATTICE_UI_EXPLORER_SEARCH_ONLY": "false",
        "LATTICE_UI_SOURCE_FILTER_ONLY": "false", "LATTICE_UI_STYLE_ONLY": "false",
        "LATTICE_UI_INPUTS_ONLY": "false", "LATTICE_UI_DIALOG_AUDIT": "false",
        "LATTICE_UI_H2": "false", "LATTICE_UI_POSTGRES_PORT": "0",
        "LATTICE_UI_BUILD_IDE_HOME": str(ide), "LATTICE_UI_IDE_HOME": str(ide),
        "UI_THEME": suite["theme"],
    })
    environment.update(suite.get("env", {}))
    if os.name == "nt":
        maven = args.binaries_dir / "ui-maven-repository" if args.binaries_dir else context.temp / "ui-maven-repository"
        command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                   str(source / "scripts/windows/review-intellij-ui.ps1"),
                   "-Themes", suite["theme"], "-OutputRoot", str(output),
                   "-IdeHome", str(ide), "-BuildIdeHome", str(ide),
                   "-JavaHome", str(context.java_home), "-DownloadDir", str(args.download_dir or context.temp),
                   "-GradleUserHome", context.env["GRADLE_USER_HOME"], "-MavenRepository", str(maven),
                   "-Cleanup" if args.cleanup else "-NoCleanup", "-Python", sys.executable]
        if args.binaries_dir:
            command += ["-BinariesDir", str(args.binaries_dir)]
            fixture_cache = args.binaries_dir / "jdbc"
            if fixture_cache.is_dir():
                command += ["-FixtureCache", str(fixture_cache)]
    else:
        command = ["bash", str(source / "scripts/linux/review-intellij-ui.sh"), suite["theme"]]
    runner.run(command, cwd=source, name=suite["id"], timeout=1500, env=environment)


def run_database_suite(suite, context):
    args, source, runner = context.args, context.source, context.runner
    mode = suite["mode"]
    properties = [f"-Plattice.compatibility.mode={mode}",
                  f"-Plattice.compatibility.java8={executable(context.java8_home, 'java')}",
                  f"-Plattice.compatibility.probe={context.probe_classes}",
                  f"-Plattice.compatibility.cache={context.jdbc_cache}",
                  f"-Plattice.compatibility.cleanup={str(args.cleanup and not args.binaries_dir and os.name != 'nt').lower()}",
                  f"-Plattice.compatibility.output={source / 'build/compatibility' / mode if mode in {'h2', 'postgres'} else source / 'build/compatibility'}"]
    if mode == "mysql":
        for server_version, driver_versions in MYSQL_MATRIX:
            port = fixture_port(args.mysql_port, 3306)
            print(f"  MySQL {server_version}; drivers {', '.join(driver_versions)}", flush=True)
            if context.native:
                from windows.native_mysql import mysql_fixture
                fixture = mysql_fixture(context.deps, server_version, args.mysql_homes.get(server_version),
                                        logs=runner.logs, runtime_dir=args.runtime_dir,
                                        sevenzip=args.sevenzip, port=port)
            else:
                fixture = mysql_server(runner, server_version, port=port, cleanup=args.cleanup)
            with fixture:
                run_gradle(runner, source, "databaseCompatibilityTest",
                           properties=properties + [f"-Plattice.compatibility.port={port}",
                                                    f"-Plattice.compatibility.server={server_version}",
                                                    f"-Plattice.compatibility.drivers={','.join(driver_versions)}"],
                           name=f"mysql-matrix-{server_version}", timeout=900)
    elif mode == "postgres":
        from postgres import POSTGRES_VERSIONS, matrix_fixture
        for server_version in POSTGRES_VERSIONS:
            port = fixture_port(None, 5432)
            with matrix_fixture(port, server_version, context.deps, args.docker or "docker", args.cleanup):
                run_gradle(runner, source, "databaseCompatibilityTest",
                           properties=properties + [f"-Plattice.compatibility.port={port}",
                                                    f"-Plattice.compatibility.server={server_version}"],
                           name=f"postgres-matrix-{server_version}", timeout=900)
    else:
        if mode == 'hsqldb':
            properties += [f"-Plattice.compatibility.drivers={','.join(HSQL_VERSIONS)}"]
        run_gradle(runner, source, "databaseCompatibilityTest", properties=properties,
                   name=f"{mode}-matrix", timeout=1800 if mode == "hsqldb" else 900)


def execute_suite(suite, context):
    """Both the CI single-suite path and the local aggregate call this function."""
    args, source, runner = context.args, context.source, context.runner
    kind = suite["kind"]
    if kind == "build":
        build_archive(context, suite["tasks"], suite["package_tasks"])
    elif kind == "gradle":
        run_gradle(runner, source, *suite["tasks"], name=suite["id"])
    elif kind == "command":
        ide = context.deps.ide(args.ide_home) if "ide" in suite["requires"] else None
        values = {"python": sys.executable, "java_home": str(context.java_home),
                  "ide_home": str(ide), "java8_home": str(context.java8_home),
                  "jdbc_cache": str(context.jdbc_cache), "binaries_dir": str(args.binaries_dir or ""),
                  "download_dir": str(args.download_dir or context.temp),
                  "gradle_user_home": context.env.get("GRADLE_USER_HOME", "")}
        runner.run([part.format_map(values) for part in suite["command"]],
                   cwd=source, name=suite["id"], timeout=suite.get("timeout", 900))
    elif kind == "live":
        command = [sys.executable, "scripts/test.py", "--suite", suite["id"],
                   "--live", "--integration-only", "--java-home", str(context.java_home),
                   "--gradle-user-home", context.env["GRADLE_USER_HOME"],
                   "--download-dir", str(context.temp / "live-dependencies")]
        command += ["--" + name for name in suite["fixtures"][test_suites.host_platform()]]
        for option in ("mysql_port", "hsqldb_port", "binaries_dir", "runtime_dir", "sevenzip", "docker"):
            value = getattr(args, option)
            if value is not None:
                command += ["--" + option.replace("_", "-"), str(value)]
        if context.env.get("LATTICE_BUILD_IDE"):
            command += ["--ide-home", context.env["LATTICE_BUILD_IDE"]]
        if context.native and 'mysql' in suite['fixtures'][test_suites.host_platform()]:
            from windows.native_mysql import mysql_home
            version = suite['mysql_native_version']
            command += ["--mysql-home", str(mysql_home(context.deps, version, args.mysql_homes.get(version)))]
        command += ["--cleanup" if args.cleanup else "--no-cleanup"]
        runner.run(command, cwd=source, name=suite["id"], timeout=900)
    elif kind == "ui":
        run_ui_suite(suite, context)
    elif kind in {"postgres-ui", "h2-ui"}:
        ide = context.deps.ide(args.ide_home)
        script = "postgres-ui-review.py" if kind == "postgres-ui" else "h2-test.py"
        command = [sys.executable, f"scripts/{script}"]
        if kind == "h2-ui":
            command += ["--ui-only"]
        command += ["--java-home", str(context.java_home), "--ide-home", str(ide),
                    "--output", str(source / "build/ui-review" / suite["id"].removeprefix("ui-")),
                    "--themes", suite["theme"], "--cleanup" if args.cleanup else "--no-cleanup",
                    "--gradle-user-home", context.env["GRADLE_USER_HOME"],
                    "--download-dir", str(args.download_dir or context.temp)]
        if args.binaries_dir:
            command += ["--binaries-dir", str(args.binaries_dir)]
        runner.run(command, cwd=source, name=suite["id"], timeout=1500)
    elif kind == "verifier":
        if context.archive is None or not context.archive.is_file():
            raise RunnerError("Plugin Verifier requires the exact built ZIP (--archive for a CI suite)")
        run_plugin_verifier(runner, source, context.env, context.temp, context.archive,
                            context.java_home, suite["version"], context.deps, args)
    elif kind == "database":
        run_database_suite(suite, context)
    else:
        raise RunnerError(f"Unsupported suite adapter: {kind}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="Full JDK 21; discover/download when missing")
    parser.add_argument("--windows-ui", action="store_true",
                        help="Include every Windows-supported UI suite; requires an interactive 1440x800+ desktop")
    parser.add_argument("--suite", dest="suites", action="append", metavar="ID",
                        help="Execute a catalog suite; repeat to select several suites")
    parser.add_argument('--ci-group', help='Execute every suite assigned to this CI group and host')
    parser.add_argument("--in-place", action="store_true", help="Run in this checkout (CI); default: isolated source copy")
    parser.add_argument("--archive", type=Path, help="Exact plugin ZIP to verify, without rebuilding it")
    parser.add_argument("--list-suites", action="store_true", help="Print the selected plan and platform exclusions; do not download/run")
    parser.add_argument("--platform", choices=sorted(test_suites.PLATFORMS), help="Platform for --list-suites only")
    parser.add_argument("--check-workflow", action="store_true", help="Validate CI/local suite registration and routing without running tests")
    parser.add_argument("--github-matrices", action="store_true", help="Emit shared UI/IDE/database matrices, including GITHUB_OUTPUT when present")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--skip-compatibility", action="store_true", help="Run tooling, build and backend suites")
    modes.add_argument("--compatibility-only", action="store_true", help="Run IDE/JDBC matrices; build an archive if needed")
    modes.add_argument("--database-compatibility-only", action="store_true", help="Run only the shared JDBC/Java matrix profile")
    parser.add_argument("--java8-home", type=Path)
    parser.add_argument("--ide-home", type=Path, help="Baseline IntelliJ 2025.1 home")
    parser.add_argument("--ide-sdk", action="append", default=[], metavar="VERSION=PATH")
    parser.add_argument("--mysql-home", action="append", default=[], metavar="VERSION=PATH")
    parser.add_argument("--verifier-jar", type=Path)
    parser.add_argument("--jdbc-dir", type=Path)
    parser.add_argument("--runtime-dir", type=Path)
    parser.add_argument("--sevenzip", type=Path)
    parser.add_argument("--docker", help="Docker CLI; selects Docker instead of native MySQL on Windows")
    parser.add_argument("--mysql-port", type=port_number)
    parser.add_argument("--hsqldb-port", type=port_number)
    add_dependency_options(parser, cleanup=True)
    args = parser.parse_args()
    metadata_only = args.list_suites or args.check_workflow or args.github_matrices
    if args.platform and not args.list_suites:
        parser.error("--platform requires --list-suites")
    if args.windows_ui and not metadata_only and os.name != "nt":
        parser.error("--windows-ui is available only on Windows")
    if (args.suites or args.ci_group) and (args.windows_ui or args.skip_compatibility or args.compatibility_only or args.database_compatibility_only):
        parser.error("--suite cannot be combined with aggregate profile flags")
    if args.suites and args.ci_group:
        parser.error('--suite and --ci-group are mutually exclusive')
    if args.windows_ui and (args.skip_compatibility or args.compatibility_only or args.database_compatibility_only):
        parser.error("--windows-ui requires the full one-shot suite")
    try:
        if args.check_workflow:
            windows = test_suites.check_workflow((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
            local = selected_suites(argparse.Namespace(platform='windows', windows_ui=True,
                                                      skip_compatibility=False, compatibility_only=False,
                                                      database_compatibility_only=False))
            if {s['id'] for s in local} != {s['id'] for s in windows}:
                raise ValueError('The full local Windows plan differs from the Windows-supported CI catalog')
            print(f"CI/local suite contract passed: {len(windows)} Windows-supported suites.")
        if args.github_matrices:
            matrices = test_suites.github_matrices()
            print(json.dumps(matrices))
            if os.environ.get("GITHUB_OUTPUT"):
                with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
                    for name, matrix in matrices.items():
                        output.write(f"{name}={json.dumps(matrix, separators=(',', ':'))}\n")
        suites = selected_suites(args) if args.list_suites or not metadata_only else []
        if args.list_suites:
            host = args.platform or test_suites.host_platform()
            print(json.dumps({"platform": host, "suites": suites,
                              "unsupported": {s["id"]: s["unsupported"][host]
                                              for s in SUITE_CATALOG["suites"] if host not in s["platforms"]}}, indent=2))
        if metadata_only:
            return 0
        args.ide_homes = path_mapping(args.ide_sdk, IDE_VERSIONS)
        mysql_versions = {v for v, _ in MYSQL_MATRIX} | {s['mysql_native_version'] for s in SUITE_CATALOG['suites'] if s['kind'] == 'live' and 'mysql_native_version' in s}
        args.mysql_homes = path_mapping(args.mysql_home, mysql_versions)
        for option in ('binaries_dir', 'download_dir', 'ide_home', 'java_home', 'java8_home',
                       'verifier_jar', 'jdbc_dir', 'runtime_dir', 'sevenzip'):
            value = getattr(args, option)
            if value is not None:
                setattr(args, option, resolved_path(value))
        if args.archive:
            args.archive = resolved_path(args.archive)
            if not args.archive.is_file():
                raise ValueError("--archive must name the exact existing plugin ZIP")
            if any(s['kind'] == 'build' for s in suites):
                raise ValueError('--archive cannot be combined with a build suite; select verifier suites or --compatibility-only')
        if (args.suites or args.ci_group) and any(s["kind"] == "verifier" for s in suites) and not args.archive and not any(s["kind"] == "build" for s in suites):
            raise ValueError("An individual verifier suite requires --archive; CI must verify the built artifact")
    except ValueError as error:
        parser.error(str(error))

    deps = Dependencies(args)
    native = (os.name == "nt" and not args.docker) or bool(args.mysql_homes)
    _, run_ide, _, step_count = validation_plan(args)
    build_for_verifier = run_ide and not any(s["kind"] == "build" for s in suites) and not args.archive
    requirements = {requirement for suite in suites for requirement in suite["requires"]}
    progress = Progress(step_count)
    active, temp_path, logs, diagnostics = "preflight", None, None, None
    try:
        with progress.step("Host and required fixture preflight"):
            if not (args.suites or args.ci_group or args.in_place):
                check_linux()
            if sys.version_info < (3, 11):
                raise RunnerError("Python 3.11 or newer is required")
            if args.mysql_port and 'mysql' in requirements:
                require_free_port(args.mysql_port)
            if args.hsqldb_port and any('hsqldb' in s.get('fixtures', {}).get(test_suites.host_platform(), []) for s in suites):
                require_free_port(args.hsqldb_port)
            needs_docker = "docker" in requirements or ("mysql" in requirements and not native)
            needs_docker |= any("postgres" in s.get("fixtures", {}).get(test_suites.host_platform(), []) for s in suites)
            if needs_docker:
                if shutil.which(args.docker or "docker") is None:
                    raise RunnerError("Docker CLI is required for the selected disposable fixtures")
                subprocess.run([args.docker or "docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            if not args.in_place:
                volume = deps.base
                while not volume.exists():
                    volume = volume.parent
                if shutil.disk_usage(volume).free < 10 * 1024**3:
                    raise RunnerError("The download volume needs at least 10 GiB free")
            print("  Selected suites: " + ", ".join(s["id"] for s in suites), flush=True)

        diagnostics = ROOT / "build/one-shot-test" / f".running-{uuid.uuid4().hex}"
        logs = diagnostics / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        with deps:
            temp_path = deps.workspace()
            with progress.step("Prepare shared dependencies and caches"):
                source = ROOT if args.in_place else copy_source(temp_path / "source")
                java_home = deps.java(args.java_home) if "java21" in requirements else None
                if java_home and (java_major(executable(java_home, "java")) != 21 or not executable(java_home, "javac").is_file()):
                    raise RunnerError("Validation requires a full JDK 21")
                environment = (prepare_proxy_environment(deps.environment(java_home), java_home, temp_path / "tools")
                               if java_home else dict(os.environ))
                environment["LATTICE_ONE_SHOT_TEMP"] = str(temp_path)
                if java_home and (args.ide_home or args.binaries_dir):
                    environment["LATTICE_BUILD_IDE"] = str(deps.ide(args.ide_home))
                if args.docker:
                    environment["LATTICE_DOCKER"] = args.docker
                runner = CommandRunner(logs, environment, progress)
                java8 = deps.java(args.java8_home, major=8, jdk=False) if "java8" in requirements else None
                jdbc_cache = (args.binaries_dir / "jdbc-cache").resolve() if args.binaries_dir else temp_path / "jdbc-cache"
                probe = temp_path / "java8-probe"
                if java8:
                    seed = args.jdbc_dir or (args.binaries_dir / "jdbc" if args.binaries_dir else None)
                    if seed:
                        if not seed.is_dir():
                            raise RunnerError("--jdbc-dir must be a directory of cached drivers")
                        if seed.resolve() != jdbc_cache.resolve():
                            shutil.copytree(seed, jdbc_cache, dirs_exist_ok=True)
                    jdbc_cache.mkdir(parents=True, exist_ok=True)
                    prepare_java8_probe(source, java_home, probe, runner, environment)
                context = argparse.Namespace(args=args, source=source, runner=runner, deps=deps, env=environment,
                                             temp=temp_path, java_home=java_home, java8_home=java8,
                                             jdbc_cache=jdbc_cache, probe_classes=probe, native=native,
                                             archive=args.archive, stamp=time.strftime("%Y%m%d-%H%M%S", time.gmtime()))
            if build_for_verifier:
                with progress.step("Build exact archive for compatibility checks"):
                    active = progress.active
                    build_archive(context, [])
            for suite in suites:
                with progress.step(suite["id"]):
                    active = suite["id"]
                    execute_suite(suite, context)
        print(f"\nPASS: {len(suites)} shared suites passed; dependencies {'cleaned' if args.cleanup else 'retained'}.", flush=True)
        if diagnostics:
            shutil.rmtree(diagnostics, ignore_errors=True)
        return 0
    except BaseException as error:
        report = None
        if temp_path and logs:
            try:
                report = preserve_failure(ROOT, temp_path, logs, active, error)
            except Exception as report_error:
                print(f"Could not preserve diagnostics: {report_error}", file=sys.stderr, flush=True)
        if diagnostics:
            shutil.rmtree(diagnostics, ignore_errors=True)
        print(f"\nFAIL: {active}: {type(error).__name__}: {error}", file=sys.stderr, flush=True)
        if report:
            print(f"Failure report and command logs: {report}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
