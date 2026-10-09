#!/usr/bin/env python3
"""Run Lattice's headless Linux validation in disposable tool/cache directories.

The runner checks tooling, Java unit tests, package integrity, live database behavior,
all supported IntelliJ targets, and the documented Java 8/JDBC compatibility matrix.
It never starts an IDE; use review-intellij-ui.sh for real UI validation.
Docker images pulled by this run are removed after their case.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import shlex
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import uuid


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
                                           text=True, encoding="utf-8", errors="replace", bufsize=1)

                def terminate_if_late():
                    if process.poll() is None:
                        timed_out.set()
                        process.kill()

                timer = threading.Timer(timeout, terminate_if_late)
                timer.daemon = True
                timer.start()
                assert process.stdout is not None
                with process.stdout:
                    for line in process.stdout:
                        output.write(line)
                        output.flush()
                        print(line, end="", flush=True)
                returncode = process.wait()
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
    if not sys.platform.startswith("linux"):
        raise RunnerError("one-shot-test.py currently supports Linux only")
    if sys.version_info < (3, 11):
        raise RunnerError("Python 3.11 or newer is required")


def require_free_port(port: int) -> None:
    with socket.socket() as listener:
        # Ignore harmless TCP TIME_WAIT sockets left by a just-stopped fixture;
        # an active listener still prevents this bind.
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
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


def find_java_home(explicit: Path | None) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(explicit.expanduser())
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]).expanduser())
    path_java = shutil.which("java")
    if path_java:
        candidates.append(Path(path_java).resolve().parent.parent)
    for candidate in candidates:
        java = candidate / "bin/java"
        javac = candidate / "bin/javac"
        if java.is_file() and javac.is_file() and java_major(java) == 21:
            return candidate.resolve()
        if explicit and candidate == explicit.expanduser():
            raise RunnerError(f"--java-home must be a full JDK 21: {candidate}")
    return None


def release_asset(major: int, image_type: str) -> dict[str, str]:
    repository = f"adoptium/temurin{major}-binaries"
    release_url = f"https://api.github.com/repos/{repository}/releases/latest"
    request = urllib.request.Request(release_url, headers={"User-Agent": "Lattice-one-shot-test/1.0",
                                                            "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        release = json.load(response)
    stem = f"OpenJDK{major}U-{image_type}_x64_linux_hotspot_"
    package = next((asset for asset in release["assets"]
                    if asset["name"].startswith(stem) and asset["name"].endswith(".tar.gz")), None)
    if package is None:
        raise RunnerError(f"Temurin release {release.get('tag_name')} has no Linux x64 Java {major} {image_type} archive")
    checksum_asset = next((asset for asset in release["assets"]
                           if asset["name"] == package["name"] + ".sha256.txt"), None)
    if checksum_asset is None:
        raise RunnerError(f"Temurin did not publish a SHA-256 file for {package['name']}")
    checksum_request = urllib.request.Request(checksum_asset["browser_download_url"],
                                             headers={"User-Agent": "Lattice-one-shot-test/1.0"})
    with urllib.request.urlopen(checksum_request, timeout=60) as response:
        checksum_text = response.read(1024).decode("ascii")
    checksum = checksum_text.split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise RunnerError(f"Temurin published an invalid Java {major} SHA-256")
    return {"link": package["browser_download_url"], "checksum": checksum, "name": package["name"]}


def safe_tar_member(member: tarfile.TarInfo, destination: Path) -> tarfile.TarInfo | None:
    root = destination.resolve()
    target = (destination / member.name).resolve()
    if not target.is_relative_to(root):
        raise RunnerError(f"Archive path escapes tool directory: {member.name}")
    if member.issym() or member.islnk():
        link_name = PurePosixPath(member.linkname)
        link_target = (target.parent / Path(*link_name.parts)).resolve()
        if link_name.is_absolute() or not link_target.is_relative_to(root):
            raise RunnerError(f"Unsafe link in runtime archive: {member.name}")
    elif not (member.isdir() or member.isfile()):
        return None
    return member


def extract_tar(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as bundle:
        data_filter = getattr(tarfile, "data_filter", None)
        for member in bundle.getmembers():
            safe = safe_tar_member(member, destination)
            if safe is None:
                continue
            if data_filter:
                safe = data_filter(safe, str(destination))
                if safe is None:
                    continue
                bundle.extract(safe, destination, filter="data")
            else:
                bundle.extract(safe, destination)


def download_runtime(tools: Path, major: int, image_type: str) -> Path:
    asset = release_asset(major, image_type)
    archive = tools / asset["name"]
    tools.mkdir(parents=True, exist_ok=True)
    print(f"  Downloading checksum-verified Temurin {major} {image_type} to the temporary workspace", flush=True)
    digest = hashlib.sha256()
    with urllib.request.urlopen(asset["link"], timeout=180) as response, archive.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != asset["checksum"]:
        archive.unlink(missing_ok=True)
        raise RunnerError(f"Java {major} archive checksum mismatch")
    extraction = tools / f"temurin-{major}-{image_type}"
    try:
        extract_tar(archive, extraction)
    finally:
        archive.unlink(missing_ok=True)
    java_files = sorted(extraction.rglob("java"))
    candidates = [path.parent.parent for path in java_files
                  if path.name == "java" and path.parent.name == "bin" and path.is_file()]
    runtime = next((path for path in candidates if java_major(path / "bin/java") == major), None)
    if runtime is None:
        raise RunnerError(f"Could not locate the downloaded Java {major} runtime")
    return runtime.resolve()


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
            keytool = java_home / "bin/keytool"
            subprocess.run([str(keytool), "-importcert", "-noprompt", "-alias", "lattice-one-shot-proxy",
                            "-file", cert, "-keystore", str(truststore), "-storepass", "changeit"],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            options += f' -Djavax.net.ssl.trustStore="{truststore}" -Djavax.net.ssl.trustStorePassword=changeit'
    result["JAVA_TOOL_OPTIONS"] = options.strip()
    return result


def copy_source(destination: Path) -> Path:
    def ignore(_directory: str, names: list[str]) -> set[str]:
        return {name for name in names if name in {
            ".git", ".gradle", "build", "out", ".idea", ".venv", "venv", "__pycache__", ".pytest_cache",
        }}
    shutil.copytree(ROOT, destination, ignore=ignore, symlinks=True)
    return destination


def project_version(source: Path) -> str:
    properties = source / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("pluginVersion="):
            return line.split("=", 1)[1].strip()
    raise RunnerError("gradle.properties has no pluginVersion")


def run_gradle(runner: CommandRunner, source: Path, *tasks: str, properties: list[str] | None = None,
               name: str, timeout: int = 900) -> None:
    command = [str(source / "gradlew"), "--no-daemon", "--console=plain", "--stacktrace", *tasks]
    command.extend(properties or [])
    runner.run(command, cwd=source, name=name, timeout=timeout)


def run_plugin_verifier(runner: CommandRunner, source: Path, env: dict[str, str],
                        temp: Path, archive: Path, java_home: Path, version: str) -> None:
    cache = temp / "verifier-cache"
    command = [sys.executable, str(source / "scripts/verify-plugin.py"), str(archive),
               "--ide-version", version, "--java-home", str(java_home),
               "--cache", str(cache), "--reports", str(source / "build/compatibility/verifier")]
    runner.run(command, cwd=source, env=env, name=f"plugin-verifier-{version}", timeout=900)
    # The matrix is deliberately serial: do not retain several multi-gigabyte IDE archives.
    for path in [cache / "ides" / version, cache / "archives/ides" / f"idea-{version}-linux.tar.gz"]:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)
    for path in (cache / "ides").glob(version + "-*") if (cache / "ides").exists() else ():
        shutil.rmtree(path, ignore_errors=True)


def docker_image_exists(image: str) -> bool:
    return subprocess.run(["docker", "image", "inspect", image], stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, check=False).returncode == 0


def remove_downloaded_image(image: str) -> None:
    last_error = ""
    for attempt in range(10):
        result = subprocess.run(["docker", "image", "rm", image], capture_output=True,
                                text=True, check=False)
        if result.returncode == 0 or not docker_image_exists(image):
            return
        last_error = result.stderr.strip()
        if attempt < 9:
            time.sleep(1)
    raise RunnerError(f"Could not remove downloaded Docker image {image}: {last_error}")


@contextmanager
def mysql_server(runner: CommandRunner, image_tag: str, port: int = 3306):
    image = f"mysql:{image_tag}"
    preexisting = docker_image_exists(image)
    name = "lattice-one-shot-" + uuid.uuid4().hex[:12]
    created = False
    log_path = runner.logs / f"mysql-{image_tag}.container.log"
    try:
        runner.run(["docker", "run", "--detach", "--name", name,
                    "--publish", f"127.0.0.1:{port}:3306", "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                    "--env", "MYSQL_ROOT_HOST=%", "--env", "MYSQL_DATABASE=shop_db", image],
                   cwd=ROOT, name=f"docker-run-mysql-{image_tag}", timeout=600)
        created = True
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            ping = subprocess.run(["docker", "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if ping.returncode == 0:
                print(f"  MySQL {image_tag} is accepting connections on 127.0.0.1:{port}", flush=True)
                break
            state = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}} {{.State.ExitCode}}", name],
                                   capture_output=True, text=True, check=False)
            if state.returncode != 0 or state.stdout.strip().startswith("false"):
                raise RunnerError(f"MySQL {image_tag} fixture stopped before becoming ready ({state.stdout.strip() or 'container unavailable'}); see {log_path}")
            time.sleep(1)
        else:
            raise RunnerError(f"MySQL {image_tag} did not become ready within 180 seconds")
        yield
    finally:
        container_exists = subprocess.run(["docker", "container", "inspect", name],
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                          check=False).returncode == 0
        if created or container_exists:
            with log_path.open("w", encoding="utf-8") as output:
                subprocess.run(["docker", "logs", name], stdout=output, stderr=subprocess.STDOUT, check=False)
            subprocess.run(["docker", "stop", "--timeout", "15", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
            subprocess.run(["docker", "rm", "--force", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        if not preexisting:
            remove_downloaded_image(image)
            print(f"  Removed downloaded Docker image {image}", flush=True)


def prepare_java8_probe(source: Path, java_home: Path, probe_classes: Path,
                        runner: CommandRunner, env: dict[str, str]) -> None:
    source_file = source / "src/test/java/com/segfault03/ideadb/Java8DriverProbe.java"
    probe_classes.mkdir(parents=True, exist_ok=True)
    runner.run([str(java_home / "bin/javac"), "--release", "8", "-encoding", "UTF-8",
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
    args = parser.parse_args()
    run_base, run_ide_compatibility, run_database_compatibility, step_count = validation_plan(args)
    progress = Progress(step_count)
    active = "preflight"
    temp_path: Path | None = None
    logs: Path | None = None
    source: Path | None = None
    diagnostics: Path | None = None
    try:
        with progress.step("Linux, Python, Docker and port preflight"):
            active = progress.active
            check_linux()
            if shutil.which("docker") is None:
                raise RunnerError("Docker CLI is required for the disposable MySQL compatibility fixtures")
            subprocess.run(["docker", "info"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            require_free_port(3306)
            if run_base:
                require_free_port(9001)
            if run_ide_compatibility:
                for port in range(19020, 19030):
                    require_free_port(port)
            required_space = 10 * 1024**3
            if shutil.disk_usage(ROOT).free < required_space:
                raise RunnerError("The workspace needs at least 10 GiB free for the isolated SDK and build downloads")
            print(f"  Host: {platform.platform()} | Python {platform.python_version()} | Docker available", flush=True)

        build_root = ROOT / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        diagnostics = build_root / "one-shot-test" / f".running-{uuid.uuid4().hex}"
        logs = diagnostics / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".lattice-one-shot-", dir=build_root) as directory:
            temp_path = Path(directory)
            tools = temp_path / "tools"
            source = copy_source(temp_path / "source")
            progress_runner_env: dict[str, str]
            runner: CommandRunner
            java_home: Path
            java8_home: Path
            probe_classes = temp_path / "java8-probe"
            gradle_home = temp_path / "gradle-home"
            development_cache = temp_path / "lattice-cache"
            jdbc_cache = temp_path / "jdbc-cache"

            with progress.step("Create temporary tool, Gradle and JDBC caches"):
                active = progress.active
                java_home = find_java_home(args.java_home) or download_runtime(tools, 21, "jdk")
                if java_major(java_home / "bin/java") != 21 or not (java_home / "bin/javac").is_file():
                    raise RunnerError("Validation requires a full JDK 21")
                progress_runner_env = prepare_proxy_environment(os.environ.copy(), java_home, tools)
                progress_runner_env.update({
                    "JAVA_HOME": str(java_home),
                    "GRADLE_USER_HOME": str(gradle_home),
                    "LATTICE_DEV_CACHE": str(development_cache),
                    "LATTICE_ONE_SHOT_TEMP": str(temp_path),
                })
                runner = CommandRunner(logs, progress_runner_env, progress)
                if run_database_compatibility:
                    java8_home = download_runtime(tools, 8, "jre")
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
                    command = [sys.executable, "scripts/test.py", "--live", "--mysql", "--hsqldb", "--integration-only"]
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
                                            archive, java_home, version)

            if run_database_compatibility:
                with progress.step("Run MySQL server and Connector/J matrix one server at a time"):
                    active = progress.active
                    for index, (server_version, driver_versions) in enumerate(MYSQL_MATRIX, 1):
                        print(f"  MySQL compatibility {index}/{len(MYSQL_MATRIX)}: server {server_version}; drivers {', '.join(driver_versions)}",
                              flush=True)
                        with mysql_server(runner, server_version):
                            properties = [
                                "-Plattice.compatibility.mode=mysql",
                                "-Plattice.compatibility.port=3306",
                                f"-Plattice.compatibility.server={server_version}",
                                f"-Plattice.compatibility.java8={java8_home / 'bin/java'}",
                                f"-Plattice.compatibility.probe={probe_classes}",
                                f"-Plattice.compatibility.cache={jdbc_cache}",
                                f"-Plattice.compatibility.output={source / 'build/compatibility'}",
                            ]
                            run_gradle(runner, source, "databaseCompatibilityTest", properties=properties,
                                       name=f"mysql-matrix-{server_version}", timeout=900)

                with progress.step("Run HSQLDB driver and Java 8 compatibility matrix one version at a time"):
                    active = progress.active
                    properties = [
                        "-Plattice.compatibility.mode=hsqldb",
                        f"-Plattice.compatibility.java8={java8_home / 'bin/java'}",
                        f"-Plattice.compatibility.probe={probe_classes}",
                        f"-Plattice.compatibility.cache={jdbc_cache}",
                        f"-Plattice.compatibility.output={source / 'build/compatibility'}",
                    ]
                    run_gradle(runner, source, "databaseCompatibilityTest", properties=properties,
                               name=f"hsqldb-matrix-{len(HSQL_VERSIONS)}-versions", timeout=1800)

        if args.skip_compatibility:
            scope = "Linux build and live functional checks (real IDE UI checks run separately)"
        elif args.compatibility_only:
            scope = "Linux IntelliJ and JDBC compatibility checks"
        elif args.database_compatibility_only:
            scope = "Linux JDBC/Java 8 compatibility matrix"
        else:
            scope = "all Linux one-shot validation steps"
        print(f"\nPASS: {scope} passed; temporary tools and caches were removed.", flush=True)
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
