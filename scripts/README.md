# Script reference

All public helper scripts use Python's standard library and run on Windows, Linux and macOS. Install Python 3.11+; use `python3` where appropriate. Java tasks require JDK 21. Commands below are run from the repository root; helper output paths are resolved from their own repository location, so they also work when invoked by absolute path from another directory.

| Script | Purpose | Additional requirements |
|---|---|---|
| `test.py` | Gradle tests and optional packaging; owns optional database fixtures | JDK 21; Docker only for `--mysql` |
| `release.py` | Validate a stable version/tag and generate patch notes/commit history | Git |
| `check-release-archive.py` | Check ZIP layout, descriptor, bytecode, licenses; generate checksums | Built plugin ZIP |
| `prepare-source-asset.py` | Download/check the pinned corresponding MySQL source archive | Network on first use |
| `verify-plugin.py` | Check exact ZIP against an IntelliJ SDK | JDK 21; network on first use or `--ide-home` |
| `common.py` | Shared cache paths, Java and Gradle discovery | Imported helper |
| `tests/` | Regression tests for development/release tooling | Git for history tests |

```text
python scripts/test.py --build
python scripts/test.py --live --mysql --hsqldb --build
python scripts/release.py --version 1.0.1
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.1.zip --version 1.0.1
python scripts/verify-plugin.py build/distributions/Lattice-1.0.1.zip --ide-version 2025.1
```

`test.py` accepts `--ide-home`, `--version` and `--notes-file`. Gradle downloads its SDK unless an override is provided. Fixture options require `--live`; omit them to use your own isolated test servers. Tests must never run against production data.

`verify-plugin.py` accepts `--ide-home`, `--java-home`, `--cache` and `--reports`. It uses checksum-verified official Linux SDK archives for static bytecode analysis on any host; it does not execute their native launchers. Each run has a fresh report/scratch directory. A local SDK override avoids downloading. Gradle builds instead use the SDK matching the host.

Downloaded development assets use the OS user-cache directory: LocalAppData on Windows, Library/Caches on macOS, XDG_CACHE_HOME or ~/.cache on Linux. Set `LATTICE_DEV_CACHE` or pass a script's `--cache` override to relocate downloads. Build outputs remain in ignored `build/`.

The root `gradlew` / `gradlew.bat` are the standard Gradle wrapper launchers. Maintainer-specific SDK/legacy-server scripts are deliberately excluded from this repository and its history.
