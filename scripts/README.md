# Script reference

All public helper scripts use Python's standard library and run on Windows, Linux and macOS. Install Python 3.11+; use `python3` where appropriate. Java tasks require JDK 21. Commands below are run from the repository root; helper output paths are resolved from their own repository location, so they also work when invoked by absolute path from another directory.

| Script | Purpose | Additional requirements |
|---|---|---|
| `test.py` | Gradle tests and optional packaging; owns optional database fixtures | JDK 21; Docker only for `--mysql` |
| `dev-deploy.py` | Test, build, discover a local IDEA, deploy Lattice and restart a running IDE | Installed IntelliJ IDEA 2025.1+; JDK 21; Git for tooling tests |
| `release.py` | Validate a stable version/tag and generate patch notes/commit history | Git |
| `check-release-archive.py` | Check ZIP layout, descriptor, bytecode, licenses; generate checksums | Built plugin ZIP |
| `prepare-source-asset.py` | Download/check the pinned corresponding MySQL source archive | Network on first use |
| `verify-plugin.py` | Check exact ZIP against an IntelliJ SDK | JDK 21; network on first use or `--ide-home` |
| `common.py` | Shared cache paths, Java and Gradle discovery | Imported helper |
| `tests/` | Regression tests for development/release tooling | Git for history tests |

```text
python scripts/test.py --build
python scripts/dev-deploy.py
python scripts/test.py --live --mysql --hsqldb --build
python scripts/release.py --version 1.0.1
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.1.zip --version 1.0.1
python scripts/verify-plugin.py build/distributions/Lattice-1.0.1.zip --ide-version 2025.1
```

`test.py` accepts `--ide-home`, `--version` and `--notes-file`. Gradle downloads its SDK unless an override is provided. Fixture options require `--live`; omit them to use your own isolated test servers. Tests must never run against production data.

## Quick local IDE deployment

From PowerShell, cmd, bash or zsh, run:

```text
python scripts/dev-deploy.py
```

The helper prints seven numbered steps, elapsed times, success/failure statuses and subprocess output. Colors are enabled in supported terminals; `--no-color` or `NO_COLOR` disables them. It discovers installed IDEA editions from standard installation directories, JetBrains Toolbox, Windows uninstall metadata, PATH and running IDEA processes. It prefers a running installation; otherwise it selects the newest compatible installation. Multiple running installations require `--ide-home` so the target is explicit.

It runs the development tooling tests, then the pure Java tests and `buildPlugin` through the committed Gradle wrapper. Compilation still uses the pinned IntelliJ 2025.1 SDK resolved by Gradle; the deployment target does not change that baseline. Java 21 is found through `JAVA_HOME`, PATH, the selected IDE's bundled runtime and standard JDK installation directories. `--java-home` overrides that search. Downloads may be needed on the first build. Live database suites and Plugin Verifier are separate checks.

The exact ZIP for the version in `gradle.properties` is checked for packaging, licenses and Java bytecode before extraction. Staging happens outside the plugins directory. Only an installation whose descriptor ID is `com.segfault03.lattice` is replaced; other plugins and IDE settings are preserved. Replacement failures restore the previous plugin. Linked installations, duplicate Lattice installations and unrelated folders named `Lattice` are rejected.

Save your IDE work before running the helper. A running IDE is asked to close **after** tests and ZIP validation pass, and deployment waits for it to exit. Windows requests closure of the selected process's IDEA frames; macOS requests application termination by PID. Linux uses `wmctrl` window-close requests when available on X11, otherwise SIGTERM so JVM shutdown hooks run; save work beforehand, especially on Wayland. Respond to any save/exit prompts. No force-kill is used: a shutdown timeout cancels deployment. After replacement the same IDE is launched again and the script checks that its process starts; this does not establish that plugin loading or interactive behavior succeeded. If IDEA was closed, it stays closed. A deployment error after shutdown still attempts to reopen the IDE, and recovery files are retained if rollback or restart fails.

Examples (use `python3` if needed):

```text
python scripts/dev-deploy.py --dry-run
python scripts/dev-deploy.py --list-ides
python scripts/dev-deploy.py --ide-home "/path/to/IntelliJ IDEA" --java-home "/path/to/jdk-21"
python scripts/dev-deploy.py --ide-home "/Applications/IntelliJ IDEA.app" --project "/path/to/project"
python scripts/dev-deploy.py --plugins-dir "/path/to/custom/IDE/plugins" --shutdown-timeout 180
```

`--dry-run` only inspects the target and prints the planned steps: it does not run tests/builds, change plugin files or restart the IDE. `--list-ides` lists compatible installations without requiring Java 21. `--startup-timeout` controls how long to wait for the restarted IDE (default 60 seconds); `--shutdown-timeout` defaults to 120 seconds. `--project` opens an explicit project after restart; otherwise IDEA's reopen-project setting applies. Launcher output is saved under ignored `build/dev-deploy/`.

Default user plugin locations follow [JetBrains' IDE directory documentation](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html): Windows `%APPDATA%/JetBrains/<selector>/plugins`, macOS `~/Library/Application Support/JetBrains/<selector>/plugins`, Linux `$XDG_DATA_HOME/JetBrains/<selector>` (default `~/.local/share`). The selector comes from the installed IDE's metadata. The helper reads custom `idea.properties`, `IDEA_PROPERTIES`, custom VM options and, when available, running JVM directory properties. Use `--plugins-dir` for an unusual profile or unsupported property expansion, and inspect the printed target with `--dry-run` first. That override controls deployment; configure IDEA itself to use the same path through its properties or VM options. Restart checks that the profile and plugin paths match the deployment target. For profiles normally launched by a separate shortcut, set `IDEA_PROPERTIES` or `IDEA_VM_OPTIONS` in the helper's environment so its launcher uses that profile too. A disabled Lattice plugin stays disabled; the script reports this so you can enable it in **Settings > Plugins**.

`verify-plugin.py` accepts `--ide-home`, `--java-home`, `--cache` and `--reports`. It uses checksum-verified official Linux SDK archives for static bytecode analysis on any host; it does not execute their native launchers. Each run has a fresh report/scratch directory. A local SDK override avoids downloading. Gradle builds instead use the SDK matching the host.

Downloaded development assets use the OS user-cache directory: LocalAppData on Windows, Library/Caches on macOS, XDG_CACHE_HOME or ~/.cache on Linux. Set `LATTICE_DEV_CACHE` or pass a script's `--cache` override to relocate downloads. Build outputs remain in ignored `build/`.

The root `gradlew` / `gradlew.bat` are the standard Gradle wrapper launchers. Maintainer-specific SDK/legacy-server scripts are deliberately excluded from this repository and its history.
