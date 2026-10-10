#!/usr/bin/env python3
"""Run Lattice's headless Linux/Windows validation in disposable tool/cache directories.

The runner checks tooling, Java unit tests, package integrity, live database behavior,
all supported IntelliJ targets, and the documented Java 8/JDBC compatibility matrix.
It never starts an IDE; use review-intellij-ui.sh for real UI validation.
Downloaded assets are removed by default; --no-cleanup retains them for reuse.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
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


ROOT = Path(__file__).resolve().parents[1]
IDE_VERSIONS = ("2025.1", "2025.2", "2025.3")
MYSQL_MATRIX = (
    ("5.5.62", ("5.1.49", "6.0.6", "8.0.33")),
    ("5.6.51", ("5.1.49", "6.0.6", "8.0.33")),
    ("5.7.44", ("5.1.49", "6.0.6", "8.0.33")),
    ("8.4.2", ("8.0.33", "8.4.0", "9.0.0", "26.7.0")),
)
HSQL_VERSIONS = (
    "2.2.9", "2.3.0", "2.3.6", "2.4.1", "2.5.0", "2.5.2",
    "2.6.1-jdk8", "2.7.0-jdk8", "2.7.3-jdk8", "2.7.4-jdk8",
)
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


def validation_plan(args: argparse.Namespace) -> tuple[bool, bool, bool, int]:
    """Return whether to run base, IDE and database checks, plus visible step count."""
    run_base = not (args.compatibility_only or args.database_compatibility_only)
    run_ide_compatibility = not (args.skip_compatibility or args.database_compatibility_only)
    run_database_compatibility = not args.skip_compatibility
    step_count = 2
    if run_base:
        step_count += 3
    elif run_ide_compatibility:
        step_count += 1  # Build the archive needed by Plugin Verifier.
    if run_ide_compatibility:
        step_count += 1
    if run_database_compatibility:
        step_count += 2
    return run_base, run_ide_compatibility, run_database_compatibility, step_count


def path_mapping(values, versions):
    result = {}
    for value in values:
        version, separator, path = value.partition('=')
        if not separator or version not in versions or not path or version in result:
            raise ValueError('Expected a unique supported VERSION=PATH: ' + value)
        result[version] = resolved_path(path)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path,
                        help="Use this full JDK 21; otherwise use JAVA_HOME/PATH, then download a temporary JDK 21")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--skip-compatibility", action="store_true",
                       help="Run tooling, build and live functional tests only; real IDE UI checks run separately")
    modes.add_argument("--compatibility-only", action="store_true",
                       help="Run the IDE verifier and JDBC compatibility matrices; build the plugin but skip ordinary tests")
    modes.add_argument("--database-compatibility-only", action="store_true",
                       help="Run only the JDBC/Java 8 matrix; useful when Plugin Verifier runs separately")
    parser.add_argument('--java8-home', type=Path, help='Java 8 runtime; download if missing')
    parser.add_argument('--ide-home', type=Path, help='Build baseline IntelliJ 2025.1 home')
    parser.add_argument('--ide-sdk', action='append', default=[], metavar='VERSION=PATH', help='Repeat for each verification SDK')
    parser.add_argument('--mysql-home', action='append', default=[], metavar='VERSION=PATH', help='Repeat for each native MySQL server')
    parser.add_argument('--verifier-jar', type=Path)
    parser.add_argument('--jdbc-dir', type=Path, help='Existing mysql/ and hsqldb/ driver folders (copied into disposable cache)')
    parser.add_argument('--runtime-dir', type=Path)
    parser.add_argument('--sevenzip', type=Path)
    parser.add_argument('--docker', help='Docker CLI; selects Docker on Windows (daemon must already be running)')
    parser.add_argument('--mysql-port', type=port_number, help='Owned MySQL fixture port; default: automatic free port for each server')
    parser.add_argument('--hsqldb-port', type=port_number, help='Owned live HSQLDB fixture port; default: automatic free port')
    add_dependency_options(parser, cleanup=True)
    args = parser.parse_args()
    try:
        args.ide_homes = path_mapping(args.ide_sdk, IDE_VERSIONS)
        args.mysql_homes = path_mapping(args.mysql_home, tuple(v for v, _ in MYSQL_MATRIX))
    except ValueError as error:
        parser.error(str(error))
    deps = Dependencies(args)
    native = os.name == 'nt' and not args.docker or bool(args.mysql_homes)
    run_base, run_ide_compatibility, run_database_compatibility, step_count = validation_plan(args)
    progress = Progress(step_count)
    active = "preflight"
    temp_path: Path | None = None
    logs: Path | None = None
    source: Path | None = None
    diagnostics: Path | None = None
    try:
        with progress.step("Host, Python, fixture and port preflight"):
            active = progress.active
            check_linux()
            if not native and shutil.which(args.docker or "docker") is None:
                raise RunnerError("Docker CLI is required for the disposable MySQL compatibility fixtures")
            if not native:
                subprocess.run([args.docker or 'docker', 'info'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            mysql_port = fixture_port(args.mysql_port, 3306)
            hsql_port = fixture_port(args.hsqldb_port, 9001) if run_base else None
            required_space = 10 * 1024**3
            volume = deps.base
            while not volume.exists():
                volume = volume.parent
            if shutil.disk_usage(volume).free < required_space:
                raise RunnerError("The download volume needs at least 10 GiB free for the isolated SDK and build downloads")
            print(f"  Host: {platform.platform()} | Python {platform.python_version()} | fixtures preflight passed", flush=True)

        build_root = ROOT / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        diagnostics = build_root / "one-shot-test" / f".running-{uuid.uuid4().hex}"
        logs = diagnostics / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        with deps:
            temp_path = deps.workspace()
            tools = temp_path / "tools"
            source = copy_source(temp_path / "source")
            progress_runner_env: dict[str, str]
            runner: CommandRunner
            java_home: Path
            java8_home: Path
            probe_classes = temp_path / "java8-probe"
            development_cache = temp_path / "lattice-cache"
            jdbc_cache = temp_path / "jdbc-cache"

            with progress.step("Create temporary tool, Gradle and JDBC caches"):
                active = progress.active
                java_home = deps.java(args.java_home)
                if java_major(executable(java_home, "java")) != 21 or not executable(java_home, "javac").is_file():
                    raise RunnerError("Validation requires a full JDK 21")
                progress_runner_env = prepare_proxy_environment(deps.environment(java_home), java_home, tools)
                progress_runner_env.update({
                    "JAVA_HOME": str(java_home),
                    "LATTICE_DEV_CACHE": str(development_cache),
                    "LATTICE_ONE_SHOT_TEMP": str(temp_path),
                })
                if args.ide_home or args.binaries_dir:
                    progress_runner_env['LATTICE_BUILD_IDE'] = str(deps.ide(args.ide_home))
                if args.docker:
                    progress_runner_env['LATTICE_DOCKER'] = args.docker
                runner = CommandRunner(logs, progress_runner_env, progress)
                if run_database_compatibility:
                    java8_home = deps.java(args.java8_home, major=8, jdk=False)
                    seed = args.jdbc_dir or (args.binaries_dir / 'jdbc' if args.binaries_dir else None)
                    if seed:
                        if not seed.is_dir():
                            raise RunnerError('--jdbc-dir must contain mysql/ and hsqldb/ folders')
                        shutil.copytree(seed, jdbc_cache, dirs_exist_ok=True)
                    prepare_java8_probe(source, java_home, probe_classes, runner, progress_runner_env)
                    print(f"  JDK 21: {java_home}\n  Java 8 runtime: {java8_home}", flush=True)
                else:
                    print(f"  JDK 21: {java_home}", flush=True)

            if run_base:
                with progress.step("Python script regression tests"):
                    active = progress.active
                    runner.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"],
                               cwd=source, name="python-tool-tests", timeout=300)

                with progress.step("Gradle unit tests and plugin build"):
                    active = progress.active
                    run_gradle(runner, source, "clean", "test", "buildPlugin",
                               name="gradle-build-and-unit-tests", timeout=1200)
                    version = project_version(source)
                    archive = source / "build/distributions" / f"Lattice-{version}.zip"
                    if not archive.is_file():
                        raise RunnerError(f"Gradle completed without producing {archive}")
                    runner.run([sys.executable, "scripts/check-release-archive.py", str(archive),
                                "--version", version], cwd=source, name="plugin-archive-check", timeout=300)

                with progress.step("Run MySQL 8.4 and HSQLDB live functional suites"):
                    active = progress.active
                    command = [sys.executable, 'scripts/test.py', '--live', '--mysql', '--hsqldb', '--integration-only',
                               '--mysql-port', str(mysql_port), '--hsqldb-port', str(hsql_port),
                               '--java-home', str(java_home), '--gradle-user-home', progress_runner_env['GRADLE_USER_HOME'],
                               '--download-dir', str(temp_path / 'live-dependencies')]
                    if progress_runner_env.get('LATTICE_BUILD_IDE'):
                        command += ['--ide-home', progress_runner_env['LATTICE_BUILD_IDE']]
                    if native:
                        from windows.native_mysql import mysql_home
                        command += ['--mysql-home', str(mysql_home(deps, '8.4.2', args.mysql_homes.get('8.4.2')))]
                    if args.binaries_dir:
                        command += ['--binaries-dir', str(args.binaries_dir)]
                    if args.runtime_dir:
                        command += ['--runtime-dir', str(args.runtime_dir)]
                    if args.sevenzip:
                        command += ['--sevenzip', str(args.sevenzip)]
                    if args.docker:
                        command += ['--docker', args.docker]
                    if not args.cleanup:
                        command += ['--no-cleanup']
                    runner.run(command, cwd=source, name="live-functional-suites", timeout=900)

            if run_ide_compatibility:
                if not run_base:
                    with progress.step("Build plugin archive for Plugin Verifier"):
                        active = progress.active
                        run_gradle(runner, source, "buildPlugin", name="gradle-plugin-package", timeout=1200)
                        version = project_version(source)
                        archive = source / "build/distributions" / f"Lattice-{version}.zip"
                        if not archive.is_file():
                            raise RunnerError(f"Gradle completed without producing {archive}")
                        runner.run([sys.executable, "scripts/check-release-archive.py", str(archive),
                                    "--version", version], cwd=source, name="plugin-archive-check", timeout=300)
                with progress.step("Verify the packaged plugin against each supported IntelliJ SDK"):
                    active = progress.active
                    archive = source / "build/distributions" / f"Lattice-{project_version(source)}.zip"
                    for index, version in enumerate(IDE_VERSIONS, 1):
                        print(f"  IDE compatibility {index}/{len(IDE_VERSIONS)}: IntelliJ IDEA {version}", flush=True)
                        run_plugin_verifier(runner, source, progress_runner_env, temp_path,
                                            archive, java_home, version, deps, args)

            if run_database_compatibility:
                with progress.step("Run MySQL server and Connector/J matrix one server at a time"):
                    active = progress.active
                    for index, (server_version, driver_versions) in enumerate(MYSQL_MATRIX, 1):
                        matrix_port = fixture_port(args.mysql_port, 3306)
                        print(f"  MySQL compatibility {index}/{len(MYSQL_MATRIX)}: server {server_version}; drivers {', '.join(driver_versions)}",
                              flush=True)
                        if native:
                            from windows.native_mysql import mysql_fixture
                            fixture = mysql_fixture(deps, server_version, args.mysql_homes.get(server_version), logs=logs,
                                                    runtime_dir=args.runtime_dir, sevenzip=args.sevenzip, port=matrix_port)
                        else:
                            fixture = mysql_server(runner, server_version, port=matrix_port, cleanup=args.cleanup)
                        with fixture:
                            properties = [
                                "-Plattice.compatibility.mode=mysql",
                                f"-Plattice.compatibility.port={matrix_port}",
                                f"-Plattice.compatibility.server={server_version}",
                                f"-Plattice.compatibility.java8={executable(java8_home, 'java')}",
                                f"-Plattice.compatibility.probe={probe_classes}",
                                f"-Plattice.compatibility.cache={jdbc_cache}",
                                f"-Plattice.compatibility.cleanup={str(args.cleanup and os.name != 'nt').lower()}",
                                f"-Plattice.compatibility.output={source / 'build/compatibility'}",
                            ]
                            run_gradle(runner, source, "databaseCompatibilityTest", properties=properties,
                                       name=f"mysql-matrix-{server_version}", timeout=900)

                with progress.step("Run HSQLDB driver and Java 8 compatibility matrix one version at a time"):
                    active = progress.active
                    properties = [
                        "-Plattice.compatibility.mode=hsqldb",
                        f"-Plattice.compatibility.java8={executable(java8_home, 'java')}",
                        f"-Plattice.compatibility.probe={probe_classes}",
                        f"-Plattice.compatibility.cache={jdbc_cache}",
                                f"-Plattice.compatibility.cleanup={str(args.cleanup and os.name != 'nt').lower()}",
                        f"-Plattice.compatibility.output={source / 'build/compatibility'}",
                    ]
                    run_gradle(runner, source, "databaseCompatibilityTest", properties=properties,
                               name=f"hsqldb-matrix-{len(HSQL_VERSIONS)}-versions", timeout=1800)

        if args.skip_compatibility:
            scope = "Linux/Windows build and live functional checks (real IDE UI checks run separately)"
        elif args.compatibility_only:
            scope = "Linux/Windows IntelliJ and JDBC compatibility checks"
        elif args.database_compatibility_only:
            scope = "Linux/Windows JDBC/Java 8 compatibility matrix"
        else:
            scope = "all Linux/Windows one-shot validation steps"
        print(f"\nPASS: {scope} passed; dependencies {'cleaned' if args.cleanup else 'retained'}.", flush=True)
        if diagnostics:
            shutil.rmtree(diagnostics, ignore_errors=True)
        return 0
    except BaseException as error:
        report: Path | None = None
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
        if isinstance(error, KeyboardInterrupt):
            print("Interrupted by user.", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
