#!/usr/bin/env python3
"""Test, build and deploy Lattice to a local IDEA installation using standard-library tools."""
import argparse
from contextlib import contextmanager
import ctypes
from dataclasses import dataclass
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

from common import ROOT, gradle_command
from release import validate_version

PLUGIN_ID = "com.segfault03.lattice"
PLUGIN_FOLDER = "Lattice"


class Progress:
    def __init__(self, color=True):
        self.color = color and sys.stdout.isatty() and "NO_COLOR" not in os.environ
        if self.color and sys.platform == "win32":
            mode = ctypes.c_ulong()
            kernel = ctypes.windll.kernel32
            kernel.GetStdHandle.restype = ctypes.c_void_p
            handle = kernel.GetStdHandle(-11)
            self.color = bool(kernel.GetConsoleMode(ctypes.c_void_p(handle), ctypes.byref(mode))
                              and kernel.SetConsoleMode(ctypes.c_void_p(handle), mode.value | 4))

    def emit(self, text, color=36):
        print(f"\033[{color}m{text}\033[0m" if self.color else text, flush=True)

    @contextmanager
    def step(self, number, label):
        started = time.monotonic()
        self.emit(f"[{number}/7] RUN   {label}")
        try:
            yield
        except BaseException:
            self.emit(f"[{number}/7] FAIL  {label} ({time.monotonic() - started:.1f}s)", 31)
            raise
        else:
            self.emit(f"[{number}/7] OK    {label} ({time.monotonic() - started:.1f}s)", 32)

    def skip(self, number, reason):
        self.emit(f"[{number}/7] SKIP  {reason}", 33)


@dataclass(frozen=True)
class Ide:
    home: Path
    info: dict
    launch: dict
    launcher: Path

    @property
    def selector(self):
        return self.info["dataDirectoryName"]

    @property
    def app(self):
        return self.home.parent if self.home.name == "Contents" and self.home.parent.suffix == ".app" else None

    @property
    def build(self):
        return tuple(int(part) for part in re.findall(r"\d+", self.info["buildNumber"]))


@dataclass(frozen=True)
class Process:
    pid: int
    executable: Path
    argv: tuple


def platform_name():
    return {"win32": "Windows", "darwin": "macOS"}.get(sys.platform, "Linux")


def installed_path(home, info_dir, relative):
    for base in (home, info_dir):
        path = (base / relative).resolve()
        if path.is_relative_to(home) and path.is_file():
            return path
    raise ValueError(f"Missing installed IDE file: {relative}")


def read_ide(location):
    location = Path(location).expanduser().resolve()
    if location.is_file():
        location = location.parent
    for base in (location, *list(location.parents)[:5]):
        for info_path in (base / "product-info.json", base / "Resources/product-info.json",
                          base / "Contents/Resources/product-info.json"):
            if not info_path.is_file():
                continue
            info = json.loads(info_path.read_text(encoding="utf-8-sig"))
            if info.get("productCode") not in {"IC", "IU", "II"}:
                raise ValueError(f"Not an IntelliJ IDEA installation: {base}")
            home = info_path.parent.parent if info_path.parent.name == "Resources" else info_path.parent
            selector = info.get("dataDirectoryName", "")
            if not selector or Path(selector).name != selector or any(c in selector for c in "/\\:"):
                raise ValueError("Invalid IDEA data directory name")
            launches = [launch for launch in info.get("launch", []) if launch.get("os") == platform_name()]
            arch = {"x86_64": "amd64", "AMD64": "amd64", "arm64": "aarch64"}.get(platform.machine(), platform.machine())
            launches.sort(key=lambda launch: launch.get("arch") != arch)
            if not launches:
                raise ValueError(f"IDE does not have a {platform_name()} launcher: {home}")
            launch = launches[0]
            ide = Ide(home, info, launch, installed_path(home, info_path.parent, launch["launcherPath"]))
            if ide.build < (251,):
                raise ValueError(f"IntelliJ IDEA 2025.1 or newer is required: {home}")
            return ide
    raise ValueError(f"No IDEA product-info.json found at {location}")


def windows_arguments(command):
    shell = ctypes.windll.shell32
    shell.CommandLineToArgvW.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
    shell.CommandLineToArgvW.restype = ctypes.POINTER(ctypes.c_wchar_p)
    count = ctypes.c_int()
    result = shell.CommandLineToArgvW(command, ctypes.byref(count))
    if not result:
        raise OSError("Cannot parse an IDEA process command line")
    try:
        return tuple(result[i] for i in range(count.value))
    finally:
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(result)


def scan_processes():
    if sys.platform == "win32":
        # Query only the current interactive session. The command is constant;
        # neither paths nor user-supplied arguments are interpolated into PowerShell.
        script = """[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;
$session = (Get-Process -Id $PID).SessionId;
@(Get-CimInstance Win32_Process | Where-Object {
    $_.SessionId -eq $session -and $_.Name -in @('idea64.exe','idea.exe','java.exe','javaw.exe')
} | Select-Object ProcessId,ExecutablePath,CommandLine) | ConvertTo-Json -Compress"""
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                                check=True, capture_output=True, text=True, encoding="utf-8-sig", timeout=25)
        rows = json.loads(result.stdout.strip() or "[]")
        if isinstance(rows, dict):
            rows = [rows]
        return [Process(int(row["ProcessId"]), Path(row["ExecutablePath"]).resolve(),
                        windows_arguments(row["CommandLine"] or "")) for row in rows if row.get("ExecutablePath")]
    if sys.platform.startswith("linux"):
        result = []
        for directory in Path("/proc").iterdir():
            if not directory.name.isdecimal():
                continue
            try:
                if directory.stat().st_uid != os.getuid():
                    continue
                argv = tuple(os.fsdecode(value) for value in (directory / "cmdline").read_bytes().split(b"\0") if value)
                result.append(Process(int(directory.name), (directory / "exe").resolve(strict=True), argv))
            except (OSError, ValueError):
                continue  # Process exited, or belongs to an inaccessible session.
        return result
    # macOS has no /proc. Keep comm (the executable path) separate from args,
    # since ps command lines do not preserve boundaries in paths with spaces.
    columns = {}
    for field in ("comm", "args"):
        output = subprocess.check_output(["ps", "-u", str(os.getuid()), "-ww", "-o", f"pid=,{field}="], text=True)
        columns[field] = dict(line.strip().split(None, 1) for line in output.splitlines() if len(line.strip().split(None, 1)) == 2)
    result = []
    for pid, executable in columns["comm"].items():
        try:
            argv = tuple(shlex.split(columns["args"].get(pid, "")))
        except ValueError:
            argv = ()
        result.append(Process(int(pid), Path(executable).resolve(), argv))
    return result


def vm_properties(argv):
    return {arg[2:].split("=", 1)[0]: arg.split("=", 1)[1].strip('"')
            for arg in argv if arg.startswith("-D") and "=" in arg}


def is_idea_process(process, ide):
    if any(arg in {"installPlugins", "inspect", "format"} for arg in process.argv[1:]):
        return False
    if process.executable == ide.launcher:
        return True
    home = vm_properties(process.argv).get("idea.home.path")
    return "com.intellij.idea.Main" in process.argv and bool(home) and Path(home).resolve() == ide.home


def matching_processes(ide, processes):
    return [process for process in processes if is_idea_process(process, ide)]


def discovery_roots(processes):
    home = Path.home()
    roots = []
    if sys.platform == "win32":
        for variable in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
            if os.environ.get(variable):
                base = Path(os.environ[variable])
                roots += [base / "JetBrains", base / "Programs/JetBrains", base / "JetBrains/Toolbox/apps"]
        import winreg
        for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
            for key_name in (r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
                             r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"):
                try:
                    with winreg.OpenKey(hive, key_name) as key:
                        for index in range(winreg.QueryInfoKey(key)[0]):
                            try:
                                with winreg.OpenKey(key, winreg.EnumKey(key, index)) as entry:
                                    name = winreg.QueryValueEx(entry, "DisplayName")[0]
                                    if "IntelliJ IDEA" in name:
                                        roots.append(Path(winreg.QueryValueEx(entry, "InstallLocation")[0]))
                            except OSError:
                                continue
                except OSError:
                    continue
    elif sys.platform == "darwin":
        roots += [Path("/Applications"), home / "Applications", home / "Library/Application Support/JetBrains/Toolbox/apps"]
    else:
        roots += [Path("/opt"), Path("/usr/local"), Path("/usr/share"), home / ".local/share/JetBrains/Toolbox/apps"]
        roots += [Path("/snap") / name / "current" for name in ("intellij-idea", "intellij-idea-community", "intellij-idea-ultimate")]
    for name in ("idea", "idea64", "idea.sh", "idea64.exe", "intellij-idea-community", "intellij-idea-ultimate"):
        launcher = shutil.which(name)
        if launcher:
            roots.append(Path(launcher).resolve().parent)
    for process in processes:
        if process.executable.name.lower() in {"idea", "idea64.exe", "idea.exe"}:
            roots.append(process.executable.parent)
        if "com.intellij.idea.Main" in process.argv:
            home_property = vm_properties(process.argv).get("idea.home.path")
            if home_property:
                roots.append(Path(home_property))
    return roots


def discover_ides(processes):
    found = {}
    for root in discovery_roots(processes):
        pending = [(root, 0)]
        while pending:
            path, depth = pending.pop()
            try:
                ide = read_ide(path)
                found[ide.home] = ide
                continue
            except (OSError, ValueError, KeyError):
                pass
            if depth < 4 and path.suffix != ".app":
                try:
                    pending.extend((child, depth + 1) for child in path.iterdir()
                                   if child.is_dir() and not child.name.startswith(".") and child.name not in {"lib", "plugins", "jbr", "cache"}
                                   and (str(path) not in {"/opt", "/usr/local", "/usr/share"}
                                        or any(name in child.name.lower() for name in ("idea", "intellij", "jetbrains"))))
                except OSError:
                    pass
    return sorted(found.values(), key=lambda ide: (ide.build, str(ide.home)), reverse=True)


def choose_ide(ides, processes):
    active = [ide for ide in ides if matching_processes(ide, processes)]
    if len(active) > 1:
        raise ValueError("Several IDEA installations are running. Select one with --ide-home.")
    if active:
        return active[0]
    if not ides:
        raise ValueError("No compatible IntelliJ IDEA found. Supply --ide-home /path/to/IDE (or .app).")
    return ides[0]


def default_paths(selector, system=None, home=None, environment=None):
    system, home, environment = system or sys.platform, home or Path.home(), os.environ if environment is None else environment
    if system == "win32":
        config = Path(environment.get("APPDATA", str(home / "AppData/Roaming"))) / "JetBrains" / selector
        return config, config / "plugins"
    if system == "darwin":
        config = home / "Library/Application Support/JetBrains" / selector
        return config, config / "plugins"
    return (Path(environment.get("XDG_CONFIG_HOME", str(home / ".config"))) / "JetBrains" / selector,
            Path(environment.get("XDG_DATA_HOME", str(home / ".local/share"))) / "JetBrains" / selector)


def read_properties(path):
    if not path.is_file():
        return {}
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = path.read_text(encoding="latin-1")
    result, continued = {}, ""
    for line in text.splitlines():
        line = continued + line.lstrip()
        if (len(line) - len(line.rstrip("\\"))) % 2:
            continued = line[:-1]
            continue
        continued = ""
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        match = re.match(r"([^:=\s]+)\s*[:=]\s*(.*)", line)
        if match:
            def unescape(value):
                return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m[1][1:], 16)) if m[1].startswith("u")
                              else {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(m[1], m[1]), value)
            result[match[1]] = unescape(match[2])
    return result


def expand_path(value, ide, properties):
    values = {**properties, "user.home": str(Path.home()), "idea.home.path": str(ide.home)}
    for _ in range(10):
        expanded = re.sub(r"\$\{([^}]+)\}", lambda match: values.get(match[1], match[0]), value)
        if expanded == value:
            break
        value = expanded
    if "${" in value or any(c in value for c in "\r\n\0"):
        raise ValueError(f"Unresolved or unsafe IDEA directory property: {value}; use --plugins-dir for a custom plugin path.")
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ValueError(f"IDEA directory paths must be absolute: {value}")
    return path.resolve()


def live_properties(ide, process):
    properties = vm_properties(process.argv)
    java_path = ide.launch.get("javaExecutablePath")
    if java_path:
        info_dir = ide.home / "Resources" if ide.app else ide.home
        try:
            java = installed_path(ide.home, info_dir, java_path)
            jcmd = java.with_name("jcmd.exe" if sys.platform == "win32" else "jcmd")
            if jcmd.is_file():
                result = subprocess.run([str(jcmd), str(process.pid), "VM.system_properties"], capture_output=True,
                                        text=True, timeout=10)
                if result.returncode == 0:
                    # Capture only path settings; do not print or store JVM properties.
                    for line in result.stdout.splitlines():
                        key, separator, value = line.partition("=")
                        if separator and key in {"idea.plugins.path", "idea.config.path", "idea.paths.selector"}:
                            properties[key] = value
        except (OSError, ValueError, subprocess.TimeoutExpired):
            pass
    return properties


def ide_paths(ide, processes, override=None):
    active = matching_processes(ide, processes)
    if len(active) > 1:
        raise ValueError("Multiple IDEA processes use this installation. Close extra instances before deployment.")
    defaults = vm_properties(ide.launch.get("additionalJvmArguments", []))
    defaults.pop("idea.home.path", None)
    config, _ = default_paths(ide.selector)
    properties = read_properties(ide.home / "bin/idea.properties")
    properties.update(read_properties(config / "idea.properties"))
    if os.environ.get("IDEA_PROPERTIES"):
        properties.update(read_properties(Path(os.environ["IDEA_PROPERTIES"]).expanduser()))
    properties = {**defaults, **properties}
    vm_file = ide.launch.get("vmOptionsFilePath")
    vm_files = []
    if vm_file:
        info_dir = ide.home / "Resources" if ide.app else ide.home
        try:
            default_vm = installed_path(ide.home, info_dir, vm_file)
            vm_files = [default_vm, config / default_vm.name]
        except ValueError:
            pass
    if os.environ.get("IDEA_VM_OPTIONS"):
        vm_files = [Path(os.environ["IDEA_VM_OPTIONS"]).expanduser()]
    for path in vm_files:
        if path.is_file():
            properties.update(vm_properties(line.strip() for line in path.read_text(encoding="utf-8").splitlines()))
    if active:
        properties.update(live_properties(ide, active[0]))
    selector = properties.get("idea.paths.selector", ide.selector)
    if any(c in selector for c in "/\\:") or selector in {"", ".", ".."}:
        raise ValueError("Invalid runtime IDEA data directory selector")
    config, plugins = default_paths(selector)
    properties.setdefault("idea.config.path", str(config))
    config = expand_path(properties["idea.config.path"], ide, properties)
    plugins = Path(override).expanduser().resolve() if override else expand_path(properties.get("idea.plugins.path", str(plugins)), ide, properties)
    if plugins == plugins.parent:
        raise ValueError("The filesystem root cannot be used as a plugins directory")
    return config, plugins


def java_21(home):
    home = Path(home).expanduser().resolve()
    java = home / "bin" / ("java.exe" if sys.platform == "win32" else "java")
    release = home / "release"
    if java.is_file() and release.is_file() and re.search(r'^JAVA_VERSION="21(?:\.|\")', release.read_text(encoding="utf-8"), re.M):
        return home
    return None


def find_java(ide, override=None):
    if override:
        found = java_21(override)
        if not found:
            raise ValueError("--java-home must point to Java 21")
        return found
    candidates = [Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else []
    executable = shutil.which("java")
    if executable:
        candidates.append(Path(executable).resolve().parent.parent)
    if ide.launch.get("javaExecutablePath"):
        info_dir = ide.home / "Resources" if ide.app else ide.home
        try:
            candidates.append(installed_path(ide.home, info_dir, ide.launch["javaExecutablePath"]).parent.parent)
        except ValueError:
            pass
    roots = [Path.home() / ".jdks", Path("/usr/lib/jvm"), Path("/Library/Java/JavaVirtualMachines"),
             Path.home() / "Library/Java/JavaVirtualMachines"]
    if sys.platform == "win32" and os.environ.get("ProgramFiles"):
        roots += [Path(os.environ["ProgramFiles"]) / name for name in ("Java", "Eclipse Adoptium", "Amazon Corretto", "Microsoft")]
    for root in roots:
        if root.is_dir():
            for child in sorted(root.iterdir()):
                candidates += [child, child / "Contents/Home"]
    for candidate in candidates:
        found = java_21(candidate)
        if found:
            return found
    raise ValueError("Java 21 not found. Install JDK 21 or supply --java-home /path/to/jdk-21.")


def build_plugin(java_home, version):
    if sys.platform == "win32" and any(c in str(ROOT) for c in '\"&|<>^%!\r\n'):
        raise ValueError("Windows checkout paths must not contain batch shell metacharacters")
    environment = {**os.environ, "JAVA_HOME": str(java_home)}
    subprocess.run(gradle_command() + ["--console=plain", "test", "buildPlugin", f"-PreleaseVersion={version}"],
                   cwd=ROOT, env=environment, check=True)
    return ROOT / "build/distributions" / f"Lattice-{version}.zip"


def descriptor_ids(path):
    jars = [path] if path.is_file() else list((path / "lib").glob("*.jar"))
    result = set()
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                descriptor = ET.fromstring(archive.read("META-INF/plugin.xml"))
                result.add(descriptor.findtext("id"))
        except (OSError, KeyError, ET.ParseError, zipfile.BadZipFile):
            continue
    return result


def existing_plugin(plugins):
    found = []
    if plugins.is_dir():
        for path in plugins.iterdir():
            ids = descriptor_ids(path)
            linked = path.is_symlink() or path.resolve() != path.absolute()
            if path == plugins / PLUGIN_FOLDER and (linked or PLUGIN_ID not in ids):
                raise ValueError(f"Refusing to replace an unrecognized or linked folder: {path}")
            if PLUGIN_ID in ids:
                if linked:
                    raise ValueError(f"Refusing to replace a linked Lattice installation: {path}")
                found.append(path)
    if len(found) > 1:
        raise ValueError("Multiple Lattice installations found; remove duplicates in Settings > Plugins first.")
    return found[0] if found else None


def stage_plugin(archive, version, plugins):
    spec = importlib.util.spec_from_file_location("lattice_archive_check", ROOT / "scripts/check-release-archive.py")
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    checker.check(archive, version)
    with zipfile.ZipFile(archive) as distribution:
        for member in distribution.infolist():
            if stat.S_IFMT(member.external_attr >> 16) == stat.S_IFLNK:
                raise ValueError("Plugin ZIP contains a symlink")
        plugins.parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix=".lattice-deploy-", dir=plugins.parent)).resolve()
        try:
            distribution.extractall(stage)  # The archive checker allowlists all member paths.
            if descriptor_ids(stage / PLUGIN_FOLDER) != {PLUGIN_ID}:
                raise ValueError("Built archive does not contain the expected Lattice plugin ID")
            return stage
        except BaseException:
            cleanup_stage(stage, plugins.parent)
            raise


class RecoveryError(RuntimeError):
    """Retain the staging directory when a rollback cannot restore the old installation."""


def deploy_plugin(stage, plugins):
    plugins.mkdir(parents=True, exist_ok=True)
    old = existing_plugin(plugins)
    backup = stage / "previous-installation"
    if old:
        os.replace(old, backup)
    try:
        os.replace(stage / PLUGIN_FOLDER, plugins / PLUGIN_FOLDER)
    except BaseException:
        if old:
            try:
                os.replace(backup, old)
            except OSError as error:
                raise RecoveryError(f"Rollback failed. Previous plugin retained at {backup}: {error}") from error
        raise


def cleanup_stage(stage, parent):
    if stage.is_symlink() or stage.resolve().parent != parent.resolve() or not stage.name.startswith(".lattice-deploy-"):
        raise ValueError(f"Refusing to clean an unowned staging directory: {stage}")
    shutil.rmtree(stage)


def request_windows_close(process):
    from ctypes import wintypes
    user = ctypes.windll.user32
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user.IsWindowVisible.argtypes = [wintypes.HWND]
    user.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]
    user.GetWindow.restype = wintypes.HWND
    user.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    windows = []
    @callback_type
    def visit(window, _):
        owner = wintypes.DWORD()
        name = ctypes.create_unicode_buffer(256)
        user.GetWindowThreadProcessId(window, ctypes.byref(owner))
        user.GetClassNameW(window, name, len(name))
        if owner.value == process.pid and name.value == "SunAwtFrame" and user.IsWindowVisible(window) and not user.GetWindow(window, 4):
            windows.append(window)
        return True
    user.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    if not user.EnumWindows(visit, 0) or not windows:
        raise RuntimeError("No closeable IDEA windows found. Exit the selected IDE manually and rerun.")
    for window in windows:
        if not user.PostMessageW(window, 0x0010, 0, 0):  # WM_CLOSE, never TerminateProcess.
            raise OSError("Could not request IDEA window closure")


def request_close(process):
    if sys.platform == "win32":
        request_windows_close(process)
    elif sys.platform == "darwin":
        script = """ObjC.import('AppKit');
function run(args) {
  var app = $.NSRunningApplication.runningApplicationWithProcessIdentifier(Number(args[0]));
  if (!app || !app.terminate) throw new Error('IDE rejected the quit request');
}"""
        subprocess.run(["osascript", "-l", "JavaScript", "-e", script, str(process.pid)], check=True, timeout=20)
    else:
        wmctrl = shutil.which("wmctrl")
        if wmctrl:
            rows = subprocess.check_output([wmctrl, "-lp"], text=True).splitlines()
            windows = [row.split()[0] for row in rows if len(row.split()) >= 3 and row.split()[2] == str(process.pid)]
            if windows:
                for window in windows:
                    subprocess.run([wmctrl, "-ic", window], check=True)
                return
        os.kill(process.pid, signal.SIGTERM)  # JVM shutdown hooks; no SIGKILL fallback.


def stop_ide(ide, processes, timeout, progress):
    for process in processes:
        # Recheck executable and arguments immediately before touching this PID.
        if process not in matching_processes(ide, scan_processes()):
            raise RuntimeError("IDE process changed during the build. Rerun to refresh the target.")
        progress.emit(f"      Requesting shutdown of IDEA PID {process.pid}; respond to any save/exit prompts.")
        request_close(process)
    deadline, next_notice = time.monotonic() + timeout, time.monotonic() + 10
    while matching_processes(ide, scan_processes()):
        if time.monotonic() >= deadline:
            raise TimeoutError("IDE did not exit in time. Deployment cancelled; no process was force-killed.")
        if time.monotonic() >= next_notice:
            progress.emit("      Still waiting for IDEA to exit...", 33)
            next_notice = time.monotonic() + 10
        time.sleep(1)


def start_ide(ide, project, timeout, progress, expected_paths=None):
    args = [str(project)] if project else []
    command = ["open", "-a", str(ide.app), "--args", *args] if ide.app else [str(ide.launcher), *args]
    output = ROOT / "build/dev-deploy"
    output.mkdir(parents=True, exist_ok=True)
    log_path = output / f"idea-launch-{time.time_ns()}.log"
    options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS} if sys.platform == "win32" else {"start_new_session": True}
    # Do not pass the build's JAVA_HOME to the launcher: IDEA uses its own runtime.
    with log_path.open("wb") as log:
        child = subprocess.Popen(command, cwd=ide.home, stdout=log, stderr=subprocess.STDOUT, **options)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        active = matching_processes(ide, scan_processes())
        if active:
            time.sleep(2)
            active = matching_processes(ide, scan_processes())
            if active:
                if expected_paths and ide_paths(ide, active) != expected_paths:
                    raise RuntimeError("IDEA restarted with different profile/plugin paths. "
                                       "Set IDEA_PROPERTIES or IDEA_VM_OPTIONS for that profile before rerunning.")
                progress.emit(f"      IDEA running (PID {active[0].pid}); launcher log: {log_path}")
                return
        if child.poll() not in {None, 0}:
            raise RuntimeError(f"IDEA launcher failed; inspect {log_path}")
        time.sleep(1)
    raise TimeoutError(f"IDEA startup was not detected; inspect {log_path}")


def run(args, progress):
    with progress.step(1, "Find IntelliJ IDEA and deployment paths"):
        processes = scan_processes()
        ides = [read_ide(args.ide_home)] if args.ide_home else discover_ides(processes)
        if args.list_ides:
            for ide in ides:
                progress.emit(f"      {ide.info['version']}  {ide.home}" + (" [running]" if matching_processes(ide, processes) else ""))
            if not ides:
                raise ValueError("No compatible IntelliJ IDEA installations found")
            return
        ide = choose_ide(ides, processes)
        config, plugins = ide_paths(ide, processes, args.plugins_dir)
        java_home = find_java(ide, args.java_home)
        version = validate_version(read_properties(ROOT / "gradle.properties")["pluginVersion"])
        project = args.project.expanduser().resolve() if args.project else None
        if project and not project.is_dir():
            raise ValueError(f"Project directory does not exist: {project}")
        existing_plugin(plugins)  # Fail before a build or shutdown on an unrelated target.
        progress.emit(f"      IDEA {ide.info['version']}: {ide.home}\n      Java 21: {java_home}\n      Plugin target: {plugins / PLUGIN_FOLDER}")
    if args.dry_run:
        for number, label in enumerate(("Run tooling tests", "Run Java tests and build plugin", "Validate and stage ZIP",
                                        "Close selected IDEA if running", "Replace Lattice plugin", "Restart IDEA if it was running"), 2):
            progress.skip(number, f"Dry run: would {label.lower()}")
        return
    with progress.step(2, "Run development tooling tests"):
        subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"], cwd=ROOT, check=True)
    with progress.step(3, "Run pure Java tests and build plugin ZIP"):
        archive = build_plugin(java_home, version)
    stage, stopped, retain = None, False, False
    try:
        with progress.step(4, "Validate and stage the exact built ZIP"):
            stage = stage_plugin(archive, version, plugins)
            progress.emit(f"      {archive}")
        active = matching_processes(ide, scan_processes())
        if active:
            with progress.step(5, "Close the selected running IDEA"):
                if ide_paths(ide, active, args.plugins_dir) != (config, plugins):
                    raise RuntimeError("IDE profile paths changed during the build. Rerun to refresh the target.")
                stop_ide(ide, active, args.shutdown_timeout, progress)
                stopped = True
        else:
            progress.skip(5, "Selected IDEA is not running")
        with progress.step(6, "Deploy Lattice with rollback on replacement failure"):
            if matching_processes(ide, scan_processes()):
                raise RuntimeError("IDEA started during deployment. Exit it and rerun.")
            deploy_plugin(stage, plugins)
            progress.emit(f"      Installed {PLUGIN_ID} {version} at {plugins / PLUGIN_FOLDER}")
            disabled = config / "disabled_plugins.txt"
            if disabled.is_file() and PLUGIN_ID in disabled.read_text(encoding="utf-8", errors="replace").splitlines():
                progress.emit("      Lattice is disabled in IDEA; enable it in Settings > Plugins to test it.", 33)
    except RecoveryError:
        retain = True
        raise
    finally:
        try:
            if stopped:
                with progress.step(7, "Restart the selected IDEA"):
                    start_ide(ide, project, args.startup_timeout, progress, (config, plugins))
            elif stage:
                progress.skip(7, "IDEA was not stopped; its running state is unchanged")
        except BaseException:
            retain = True
            if stage:
                progress.emit(f"      Recovery files retained at {stage}", 33)
            raise
        finally:
            if stage and not retain:
                cleanup_stage(stage, plugins.parent)
    progress.emit("Done. Tests passed and the built Lattice plugin is deployed.", 32)


def positive_timeout(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("Timeout must be a positive number of seconds")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, epilog="Save IDE work before deploying. No force-kill is used.")
    parser.add_argument("--ide-home", type=Path, help="Installed IDEA directory or macOS .app; otherwise discover it")
    parser.add_argument("--plugins-dir", type=Path, help="Explicit custom user plugins directory")
    parser.add_argument("--java-home", type=Path, help="JDK 21 directory; otherwise discover Java 21")
    parser.add_argument("--project", type=Path, help="Project to open after restart (otherwise IDEA's reopen setting applies)")
    parser.add_argument("--dry-run", action="store_true", help="Print targets and planned steps without building, deploying or restarting")
    parser.add_argument("--list-ides", action="store_true", help="List discovered compatible IDEA installations and exit")
    parser.add_argument("--shutdown-timeout", type=positive_timeout, default=120, metavar="SECONDS")
    parser.add_argument("--startup-timeout", type=positive_timeout, default=60, metavar="SECONDS")
    parser.add_argument("--no-color", action="store_true", help="Disable ANSI colors (also honors NO_COLOR)")
    args = parser.parse_args(argv)
    progress = Progress(not args.no_color)
    try:
        run(args, progress)
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        progress.emit(f"ERROR: {error}", 31)
        return 1
    except KeyboardInterrupt:
        progress.emit("Cancelled.", 33)
        return 130


if __name__ == "__main__":
    sys.exit(main())
