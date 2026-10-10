# Script reference

Install Python 3.11+ for Python entry points; use `python3` where appropriate. Platform support differs by script, so use the map below rather than assuming every Python script is portable. Java tasks use JDK 21, found locally or downloaded when missing. Commands below are run from the repository root; helper output paths are resolved from their own repository location, so they also work when invoked by absolute path from another directory.

See [Running Windows scripts locally](WINDOWS_SCRIPTS.md) for every Windows command, complete options, examples and defaults.

## Binary paths, downloads and cleanup

Every dependency-using Python CLI supports `--download-dir PATH`, `--cleanup` / `--no-cleanup`, `--binaries-dir PATH` and `--gradle-user-home PATH`. Supply individual paths to avoid downloads; invalid explicit paths fail with an error. Omitted dependencies are discovered in the environment/PATH or the supplied asset collection, then downloaded when needed. Python 3.11+ and PowerShell on Windows are entry-point prerequisites. The build always uses the committed Gradle wrapper, whose distribution and Maven dependencies go to the selected Gradle cache.

Each run creates a unique `lattice-run-*` directory under the download parent (default OS user cache, or `LATTICE_DEV_CACHE`). Cleanup removes only that directory, including on failure; it leaves the parent, preexisting assets, explicit Gradle/Maven caches and final output/report files intact. Retained dependency workspaces are searched on later runs so `--no-cleanup` allows offline reuse. Supply `--gradle-user-home` or set `GRADLE_USER_HOME` for an ongoing shared cache; otherwise the Gradle cache is private to its run. `test.py`, `one-shot-test.py` and the cancellation probe default to cleanup; other helpers default to retention. Legacy `--cache` is still supported by source/verifier helpers: it is searched for existing assets; with cleanup enabled new downloads go into the owned workspace. Custom IDE homes are SDK directories, not installers.

On Windows, helpers add `-Djava.net.preferIPv4Stack=true` to `JAVA_TOOL_OPTIONS` before launching Java, including the Gradle wrapper download. This avoids socket timeouts seen with Java's default dual-stack mode. Existing Java options are preserved; an explicitly configured `-Djava.net.preferIPv4Stack=` value takes precedence.

Dependency/cache paths expand environment variables before resolution. CMD expands `%USERPROFILE%` in commands; PowerShell examples use `$env:USERPROFILE`. An unresolved variable is rejected before creating a cache, rather than creating a literal variable-named directory in the checkout.

Temporary commands use `processes.py`, which owns whole process trees and closes their handles on success, failure, timeout or interruption. Windows uses kill-on-close jobs; PowerShell launchers also supervise their Python runner if the PowerShell parent is killed. Unix commands use isolated process groups with a separate lifeline guardian for runner death; Linux UI launchers include Xvfb, window managers, Gradle and test IDEs in this supervision. A successful `dev-deploy` IDE restart is an explicit application handoff and remains open for use. `--no-cleanup` retains files, not temporary processes.

The optional asset collection layout is `ides/<version>/` (product-info.json at the IDE home, or one extracted subdirectory), `mysql/<version>/` (root or nested bin/mysqld.exe), `runtimes/java-8/` (an extracted runtime), `runtimes/vc2010/`, `runtimes/vc2013/`, `jdbc/mysql/`, `jdbc/hsqldb/`, `tools/verifier/verifier-cli-1.410-all.jar`, `tools/junit/`, and `sources/`. No machine-specific location is hardcoded. `prepare-source-asset.py --source-archive PATH` reuses the pinned Connector/J 26.7.0 source; `release.py --git PATH` selects Git, with portable MinGit download on Windows if Git is missing. `check-release-archive.py` needs only Python and input files, so it has no download/cleanup options. Deployment still requires an installed target IDE; `dev-deploy.py --build-ide-home PATH` selects its build SDK. Dry-run/list modes never download or install tools.

All public Python commands have 1:1 PowerShell launchers. Their Python options are forwarded unchanged, including `--help`; `-Python PATH` selects the interpreter:

```powershell
$assets = 'C:\path\to\test-binaries'
.\scripts\windows\test.ps1 --live --mysql --hsqldb --build --binaries-dir $assets --download-dir "$assets\downloads" --no-cleanup
.\scripts\windows\one-shot-test.ps1 --binaries-dir $assets --download-dir "$assets\downloads" --gradle-user-home "$env:USERPROFILE\.gradle" --cleanup
.\scripts\windows\probe-hsqldb-cancellation.ps1 --java-home "$assets\ides\2025.1\jbr"
```

The two Windows UI scripts use native PowerShell flags: `-JavaHome`, `-IdeHome` (IDE to launch), `-BuildIdeHome` (2025.1 compile SDK), `-DownloadDir`, `-BinariesDir`, `-GradleUserHome`, `-Cleanup` / `-NoCleanup`, and `-Python`. Review additionally accepts `-FixtureCache` (mysql/ and hsqldb/ JDBC folders) and `-MavenRepository` (a reusable Maven-layout fixture repository). Missing jars are prepared for both capture and review. The UI bootstrap defaults to retention; cleanup leaves screenshots and logs under build/. Use an interactive desktop with the test IDE in the foreground.

```powershell
.\scripts\windows\review-intellij-ui.ps1 -Themes @('ExperimentalDark') -StyleOnly -BinariesDir $assets -DownloadDir "$assets\downloads" -GradleUserHome "$env:USERPROFILE\.gradle" -Cleanup
```

## Platform support and reuse map

| Support | Scripts | Reuse and limits |
|---|---|---|
| Windows, Linux and macOS | `test.py`, `dev-deploy.py`, `release.py`, `check-release-archive.py`, `prepare-source-asset.py`, `verify-plugin.py`, `ui-preview.py` | Python entry points use the standard library. `test.py`, `dev-deploy.py`, `prepare-source-asset.py`, `verify-plugin.py` and `ui-preview.py` reuse `common.py`; `test.py` and `dev-deploy.py` reuse release helpers, and deployment imports the archive checker. `ui-preview.py` compiles the production Swing UI and finds or downloads missing SDK/runtime jars. Every public Python CLI has a matching `.ps1` launcher. |
| Windows, Linux and macOS (imported module) | `common.py` | Shared cache paths, Java discovery and Gradle wrapper selection; it is not a command-line entry point. |
| Linux and Windows | `one-shot-test.py` / `one-shot-test.ps1` | Same isolated build, live and compatibility matrix. Windows uses native ZIP MySQL servers by default; Linux uses Docker. |
| Linux with X11/Xvfb | `capture-intellij-ui.sh`, `review-intellij-ui.sh` | Bash launchers for the real IDE UI suite. Headless mode needs Xvfb and X11 tools; review also uses `curl` and Python. They share the Gradle UI test but currently duplicate theme and JDBC fixture metadata with the PowerShell wrappers. |
| Windows interactive desktop | `capture-intellij-ui.ps1`, `review-intellij-ui.ps1` | PowerShell launchers for the same real IDE UI suite. The primary display must be at least 1440×800 and the test IDE must remain in the foreground. |
| Windows, Linux and macOS | `probe-hsqldb-cancellation.py` / `.ps1` / `.sh` | One bounded diagnostic JVM; Python handles the timeout, with no GNU coreutils requirement. |
| Windows, Linux and macOS | `tests/` | Python tooling regression suite; platform-specific behavior is tested with mocks where practical. |

Common Python commands stay at the root of `scripts/`. PowerShell commands and helpers live in `scripts/windows/`; Bash commands live in `scripts/linux/`. Shared Python utilities live in `common.py`, `dependencies.py` and `processes.py`; native MySQL support lives in `windows/native_mysql.py`, and release helpers live in `release.py`. The Windows UI bootstrap uses `windows/windows-script-dependencies.py` and `windows/windows-common.ps1` to provision dependencies and preserve argument arrays; desktop setup remains in PowerShell. These helpers and the Python modules are imported/internal entry points, not separate user commands. Legacy workstation runners have been retired from this tree and preserved outside the repository.

| Script | Purpose | Additional requirements |
|---|---|---|
| `test.py` / `.ps1` | Gradle tests and optional packaging; owns optional database fixtures | JDK 21 is found/downloaded; native MySQL on Windows, Docker on Linux/macOS |
| `one-shot-test.py` | Headless Linux/Windows validation runner with full, routine-check and compatibility-only modes; real IDE UI checks run separately | Linux or Windows, Python 3.11+, network for missing assets, at least 10 GiB free; Docker required only for Docker fixtures |
| `ui-preview.py` | Render production Swing screens and refresh the five README screenshots; optionally build a light/dark gallery | Windows, Linux or macOS; JDK 21; local/cached IntelliJ 2025.1 SDK, JUnit, Hamcrest and FlatLaf, or network to download missing assets |
| `dev-deploy.py` | Test, build, discover a local IDEA, deploy Lattice and restart a running IDE | Installed IntelliJ IDEA 2025.1+; JDK 21; Git for tooling tests |
| `release.py` | Validate a stable version/tag and generate patch notes/commit history | Git |
| `check-release-archive.py` | Check ZIP layout, descriptor, bytecode, licenses; generate checksums | Built plugin ZIP |
| `prepare-source-asset.py` | Download/check the pinned corresponding MySQL source archive | Network on first use |
| `verify-plugin.py` | Check exact ZIP against an IntelliJ SDK | JDK 21; network on first use or `--ide-home` |
| `capture-intellij-ui.sh` / `capture-intellij-ui.ps1` | Launch IDEA with the built plugin and capture the actual tool window; Linux uses Xvfb and Windows uses the active desktop | Linux: JDK 21, Xvfb and `xdpyinfo` (x11-utils), or a graphical display. Windows: JDK 21, an interactive desktop at least 1440×800, and the test IDE in the foreground. Network on first IDE build |
| `review-intellij-ui.sh` / `review-intellij-ui.ps1` | Capture real-IDE UI flows across bundled themes (seven by default), with optional focused styling checks | Linux: JDK 21, Xvfb, x11-utils, xfwm4 or Openbox, Python 3, curl. Windows: JDK 21, an interactive desktop at least 1440×800, and the test IDE in the foreground. Network for JDBC fixtures/downloads |
| `probe-hsqldb-cancellation.py` / `.sh` / `.ps1` | Measure real HSQLDB JDBC cancellation and subsequent connection usability in an isolated in-memory database | Python 3.11+; JDK 21 found/downloaded; optional HSQLDB jar path (2.7.4 found/downloaded by default) |
| `common.py` | Shared cache paths, Java and Gradle discovery | Imported helper |
| `tests/` | Regression tests for development/release tooling | Git for history tests |

```text
python scripts/test.py --build
python scripts/dev-deploy.py
python scripts/test.py --live --mysql --hsqldb --build
python scripts/one-shot-test.py
python scripts/release.py --version 1.0.1
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.1.zip --version 1.0.1
python scripts/verify-plugin.py build/distributions/Lattice-1.0.1.zip --ide-version 2025.1
```

`test.py` accepts `--java-home` to select a JDK 21 explicitly, plus `--ide-home`, `--version` and `--notes-file`. `--integration-only` runs only the live integration and fallback-driver suites; it requires `--live`. Gradle downloads its SDK unless an override is provided. Fixture options require `--live`; omit them to use your own isolated test servers. Tests must never run against production data.

Owned fixtures choose free loopback ports automatically, so an existing MySQL on 3306 or HSQLDB on 9001 does not block validation. Use `--mysql-port PORT` / `--hsqldb-port PORT` to request fixed free ports; `0` requests automatic selection. Without an owned fixture flag, these options select an external test server, defaulting to 3306/9001. Direct Gradle runs accept `LATTICE_TEST_MYSQL_PORT` / `LATTICE_TEST_HSQLDB_PORT` environment variables. Windows jobs stop owned fixture process trees even if the supervising runner is killed.

## Linux and Windows one-shot validation

Run `python scripts/one-shot-test.py` (or `powershell -File scripts/windows/one-shot-test.ps1`) for the same eight steps on either host: tooling tests, Gradle unit tests/package validation, live MySQL/HSQLDB and fallback-driver tests, IntelliJ 2025.1–2025.3 verification, thirteen Connector/J/server combinations across MySQL 5.5.62/5.6.51/5.7.44/8.4.2, and ten HSQLDB/Java 8 combinations. This runner does not open an IDE; use the separate UI review entry point.

Windows uses portable native MySQL servers, initialized with fresh data under the run's workspace. Existing installation data and `fixture-data` are never used. Missing Windows servers come from Oracle's ZIP archives with published MD5 checksums; missing Microsoft CRT files are extracted from signature-verified redistributables into a private PATH, with a checksum-pinned portable 7-Zip extractor. No server service or system runtime is installed. Linux uses Docker; a running Docker daemon is a prerequisite. `--docker PATH` selects an explicit CLI and also opts Windows into Docker. Containers/processes owned by the run are always stopped, even with `--no-cleanup`. Downloaded Docker images are retained with `--no-cleanup`; existing images are always retained. Docker's image storage is controlled by its daemon, independently of `--download-dir`.

Use `--java-home PATH`, `--java8-home PATH`, `--ide-home PATH` for the build baseline, repeated `--ide-sdk VERSION=PATH`, repeated `--mysql-home VERSION=PATH`, `--verifier-jar PATH`, `--jdbc-dir PATH` (containing `mysql/` and `hsqldb/`), `--runtime-dir PATH`, or `--sevenzip PATH`. A MySQL home can be an installation root, a version folder containing an extracted ZIP, or `mysqld.exe`. `--binaries-dir PATH` discovers these inputs from the reusable layout described below. JDBC inputs are copied into the owned cache before testing.

`--skip-compatibility` runs tooling, build and live checks; `--compatibility-only` builds the ZIP and runs both compatibility matrices; `--database-compatibility-only` runs only the JDBC/Java 8 matrix. Existing Git/IDE/build caches are excluded from the source copy. Every failure returns nonzero and saves command logs plus a summary under `build/one-shot-test/failure-<UTC timestamp>/`. Cleanup defaults on; use `--no-cleanup` to retain source, downloads and scratch for investigation. A retained matrix also keeps its JDBC jars. On Windows, JAR deletion waits until the diagnostic JVM has exited, because legacy drivers can keep JAR handles open even after class-loader disposal. The runner prints its owned workspace location. MySQL and HSQLDB fixtures use automatically selected free loopback ports, including each compatibility server. `--mysql-port PORT` / `--hsqldb-port PORT` request fixed ports for MySQL and the live HSQLDB fixture; explicit ports must be free.

## UI previews and screenshots

For quick component layout feedback and the five README images, run:

```sh
python scripts/ui-preview.py
```

This renders production Swing components with fixture data and a headless IntelliJ test application that supplies platform services such as the native Action System. It opens no IDE window. It discovers local JDK 21, IntelliJ 2025.1 and JUnit/Hamcrest jars, downloading missing inputs plus checksum-verified FlatLaf under `--download-dir`. Supply `--flatlaf-jar`, `--junit-jar` and `--hamcrest-jar` to override individual jars. `--all-previews` adds light/dark gallery variants; `--output` selects another gallery directory; `--compare-with` adds a before/after view. The default run refreshes only the five tracked PNGs under `screenshots/`. See [UI preview instructions](UI_PREVIEW.md).

For screenshots and interactions inside a real test IDE under Xvfb, run:

```sh
LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh
```

This runs Gradle's `uiScreenshotTest` task and writes full IDE screenshots and live-runtime evidence under `build/ui-test-results/`. It builds and loads the plugin into the test IDE and uses JetBrains UI automation; the fixture directory must be populated and passed to the IDE for Maven driver discovery. The multi-flow review command below fetches fixtures and sets this property automatically.

For the multi-flow, multi-theme UI suite and automatic fixture setup, run:

```sh
./scripts/linux/review-intellij-ui.sh
./scripts/linux/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

Results are written under `build/ui-review/<theme-id>/` with screenshots, logs, JUnit results and runtime evidence. Linux runs use Xvfb at 1920×1080, 24-bit color and scale 1, with an available window manager for native dialog borders. Set `XVFB_DISPLAY` if `:99` is occupied. For the focused connection-field alignment pass, set `LATTICE_UI_INPUTS_ONLY=true`; set `LATTICE_UI_REVIEW_OUTPUT` to choose another results directory. The disposable HSQLDB cancellation probe is available as `./scripts/linux/probe-hsqldb-cancellation.sh`.

Set `LATTICE_UI_IDE_HOME` to an installed Linux IDEA directory to launch another version with its bundled JBR, while compilation stays on the pinned 2025.1 / Java 21 SDK. This applies to both real IDE scripts; direct Gradle users can pass `-Plattice.ui.ide.home=/path/to/idea`. The test verifies the actual theme after startup, captures open text/numeric cell editors, auto-refresh options and menus, checks painted white action glyphs, and exercises the explorer's native hover toolbar. IDEA 2026.2.3 also provides `Islands Light`, `Islands Dark` and `Islands Darcula`; pass those quoted IDs explicitly alongside the seven classic IDs when reviewing that version.

For a faster review of connection-dialog spacing, explorer overflow, table editor/menu surfaces, auto-refresh sizing and active glyph colors, set `LATTICE_UI_STYLE_ONLY=true` (direct Gradle: `-Plattice.ui.styleOnly=true`). This still opens and edits the production UI inside the full IDE, then stops after those checks; omit it for the complete review.

Linux commit and release CI run the complete Dark-theme review followed by the focused Light-theme review on the pinned IDE. Java and script tests continue to run on Linux, Windows and macOS. The newer-IDE/theme override is available for local compatibility reviews.

```bash
LATTICE_UI_IDE_HOME=/path/to/idea-2026.2.3 ./scripts/linux/review-intellij-ui.sh ExperimentalLight ExperimentalLightWithLightHeader IntelliJ JetBrainsLightTheme "Islands Light" ExperimentalDark Darcula JetBrainsHighContrastTheme "Islands Dark" "Islands Darcula"
```

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

`verify-plugin.py` accepts `--ide-home`, `--java-home`, `--verifier-jar`, `--cache` and `--reports`, plus the shared dependency options. It uses checksum-verified official Linux SDK archives for static bytecode analysis on any host; it does not execute their native launchers. Each run has a fresh report/scratch directory. A local SDK override avoids downloading. Gradle builds instead use the SDK matching the host.

Downloaded development assets use the OS user-cache directory: LocalAppData on Windows, Library/Caches on macOS, XDG_CACHE_HOME or ~/.cache on Linux. Set `LATTICE_DEV_CACHE` or pass a script's `--cache` override to relocate downloads. Build outputs remain in ignored `build/`.

The root `gradlew` / `gradlew.bat` are the standard Gradle wrapper launchers. Public Python helpers live in `scripts/`; platform launchers live in `scripts/windows/` and `scripts/linux/`. Keep workstation-specific instructions and private tools outside the repository. SDKs and native test binaries stay outside this repository. The five README screenshots remain tracked; other generated screenshots and build outputs are ignored. See the [documentation index](README.md) for the project layout.
