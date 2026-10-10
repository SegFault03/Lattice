"""Platform-neutral development paths and Java discovery."""
import os
import re
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]


def resolved_path(value):
    expanded = os.path.expandvars(str(value))
    if re.search(r'%[^%/\\]+%|\$\{[^}]+\}|\$[A-Za-z_]\w*', expanded):
        raise ValueError(f'Path contains an unresolved environment variable: {value}')
    return Path(expanded).expanduser().resolve()


def cache_directory():
    override = os.environ.get("LATTICE_DEV_CACHE")
    if override:
        return resolved_path(override)
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / "AppData/Local")))
    elif sys.platform == "darwin":
        base = Path.home() / "Library/Caches"
    else:
        base = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache")))
    return base / "lattice-development"


def java_command(home=None):
    home = home or os.environ.get("JAVA_HOME")
    if home:
        command = Path(home).expanduser() / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not command.is_file():
            raise ValueError("JAVA_HOME/--java-home must contain bin/java")
        return str(command.resolve())
    command = shutil.which("java")
    if not command:
        raise ValueError("Install JDK 21 and set JAVA_HOME or put java on PATH")
    return command


def gradle_command(root=None):
    # Windows batch launch requires cmd.exe. Arguments are controlled by the CLI,
    # with shell metacharacters rejected before this command is constructed.
    if os.name == "nt":
        # Callers set cwd to the selected checkout. A relative batch name prevents cmd.exe from
        # stripping the first pair of quotes when the checkout has spaces.
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", r".\gradlew.bat"]
    return [str((root or ROOT) / "gradlew")]
