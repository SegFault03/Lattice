# Script reference

Python helper scripts use Python's standard library and run on Windows, Linux and macOS. Install Python 3.11+; use `python3` where appropriate. The real IDE UI capture helper is Bash-only and requires a Linux display. Java tasks require JDK 21. Commands below are run from the repository root; helper output paths are resolved from their own repository location, so they also work when invoked by absolute path from another directory.

| Script | Purpose | Additional requirements |
|---|---|---|
| `test.py` | Gradle tests and optional packaging; owns optional database fixtures | JDK 21; Docker only for `--mysql` |
| `one-shot-test.py` | Headless Linux validation runner with full, routine-check and compatibility-only modes; real IDE UI checks run separately | Linux, Python 3.11+, Docker, network access, at least 10 GiB free; JDK 21 is found or downloaded |
| `ui-preview.py` | Render production Swing screens and refresh the five README screenshots; optionally build a light/dark gallery | JDK 21; cached IntelliJ 2025.1 SDK and Gradle JUnit/Hamcrest jars; network on first use for FlatLaf |
| `dev-deploy.py` | Test, build, discover a local IDEA, deploy Lattice and restart a running IDE | Installed IntelliJ IDEA 2025.1+; JDK 21; Git for tooling tests |
| `release.py` | Validate a stable version/tag and generate patch notes/commit history | Git |
| `check-release-archive.py` | Check ZIP layout, descriptor, bytecode, licenses; generate checksums | Built plugin ZIP |
| `prepare-source-asset.py` | Download/check the pinned corresponding MySQL source archive | Network on first use |
| `verify-plugin.py` | Check exact ZIP against an IntelliJ SDK | JDK 21; network on first use or `--ide-home` |
| `capture-intellij-ui.sh` | Launch IDEA with the built plugin under Xvfb and capture the actual tool window | Linux; JDK 21; Xvfb and `xdpyinfo` (x11-utils), or a graphical display; network on first IDE build |
| `review-intellij-ui.sh` | Capture real-IDE UI flows across bundled themes (seven by default), with optional focused styling checks | Linux; JDK 21; Xvfb; x11-utils; xfwm4 or Openbox; Python 3; curl; network for real JDBC fixtures/downloads |
| `probe-hsqldb-cancellation.sh` | Measure real HSQLDB JDBC cancellation and subsequent connection usability in an isolated in-memory database | Bash; JDK 21; `timeout`; optional HSQLDB jar path (bundled 2.7.4 by default) |
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

`test.py` accepts `--ide-home`, `--version` and `--notes-file`. `--integration-only` runs only the live integration and fallback-driver suites; it requires `--live`. Gradle downloads its SDK unless an override is provided. Fixture options require `--live`; omit them to use your own isolated test servers. Tests must never run against production data.

## Linux one-shot validation

Run `python scripts/one-shot-test.py` to execute the documented headless validation matrix. The script first checks Linux, Docker and required ports, then creates a temporary workspace containing isolated Gradle, development and JDBC caches. It uses a JDK 21 from `--java-home`, `JAVA_HOME` or `PATH`, and downloads a temporary JDK 21 only when none is available. Java 8 is downloaded only for compatibility modes. Python/Gradle/verifier downloads are directed into the temporary workspace.

The default eight visible steps run Python tooling tests; Gradle unit tests and plugin packaging; MySQL 8.4/HSQLDB functional fixtures plus fallback-driver checks; the IntelliJ 2025.1–2025.3 verifier matrix; 13 Connector/J/server combinations across four MySQL server versions; and ten HSQLDB driver/server versions. Database servers and each IDE SDK are processed serially. A fetched Docker image is removed after its server case, and each compatibility driver JAR is removed after its checks. An image already present before the run is left untouched. This helper does not open an IDE window or render screenshots. Run `python scripts/ui-preview.py` for standalone component images or `./scripts/review-intellij-ui.sh ExperimentalDark` for real-IDE UI flows.

Use `--skip-compatibility` for the five-step isolated build and live functional checks without downloading compatibility SDKs and JDBC drivers. Actions runs routine checks through `scripts/test.py` so the enhanced Gradle cache remains effective. Its pull-request IDE matrix downloads the Linux merge-commit ZIP already built by the baseline and verifies that exact artifact against the three supported IDE versions on separate runners. A parallel JDBC job uses `--database-compatibility-only` for the isolated JDBC/Java 8 matrix. Neither job repeats unit or UI tests or rebuilds the plugin ZIP. Generated `out/` IDE installations are excluded from the isolated source copy, and exited MySQL fixtures retain startup logs before explicit cleanup. The release workflow already verifies its exact archive, so it uses `--database-compatibility-only` to run only the MySQL and HSQLDB/Java 8 matrix.

Every failed command stops the run and returns a nonzero exit status. The temporary workspace is still removed; command logs and a failure summary are copied to `build/one-shot-test/failure-<UTC timestamp>/`. On success all runner-created caches and downloaded tools are removed. The script requires network access, Docker and at least 10 GiB free workspace space. Port 3306 is needed for either functional or MySQL matrix checks; port 9001 for base HSQLDB functional checks; and ports 19020–19029 for Plugin Verifier. The temporary workspace is created under ignored `build/`, which avoids small `/tmp` mounts.

## UI previews and screenshots

For quick component layout feedback and the five README images, run:

```sh
python scripts/ui-preview.py
```

This renders production Swing components with fixture data and a headless IntelliJ test application that supplies platform services such as the native Action System. It opens no IDE window. It requires JDK 21, the cached IntelliJ 2025.1 SDK and Gradle's cached JUnit/Hamcrest jars; the first run downloads checksum-verified FlatLaf into ignored build output. `--all-previews` adds light/dark gallery variants; `--output` selects another gallery directory; `--compare-with` adds a before/after view. The default run refreshes only the five tracked PNGs under `screenshots/`. See [UI preview instructions](UI_PREVIEW.md).

For screenshots and interactions inside a real test IDE under Xvfb, run:

```sh
LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/capture-intellij-ui.sh
```

This runs Gradle's `uiScreenshotTest` task and writes full IDE screenshots and live-runtime evidence under `build/ui-test-results/`. It builds and loads the plugin into the test IDE and uses JetBrains UI automation; the fixture directory must be populated and passed to the IDE for Maven driver discovery. The multi-flow review command below fetches fixtures and sets this property automatically.

For the multi-flow, multi-theme UI suite and automatic fixture setup, run:

```sh
./scripts/review-intellij-ui.sh
./scripts/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

Results are written under `build/ui-review/<theme-id>/` with screenshots, logs, JUnit results and runtime evidence. Linux runs use Xvfb at 1920×1080, 24-bit color and scale 1, with an available window manager for native dialog borders. Set `XVFB_DISPLAY` if `:99` is occupied. For the focused connection-field alignment pass, set `LATTICE_UI_INPUTS_ONLY=true`; set `LATTICE_UI_REVIEW_OUTPUT` to choose another results directory. The disposable HSQLDB cancellation probe is available as `./scripts/probe-hsqldb-cancellation.sh`.

Set `LATTICE_UI_IDE_HOME` to an installed Linux IDEA directory to launch another version with its bundled JBR, while compilation stays on the pinned 2025.1 / Java 21 SDK. This applies to both real IDE scripts; direct Gradle users can pass `-Plattice.ui.ide.home=/path/to/idea`. The test verifies the actual theme after startup, captures open text/numeric cell editors, auto-refresh options and menus, checks painted white action glyphs, and exercises the explorer's native hover toolbar. IDEA 2026.2.3 also provides `Islands Light`, `Islands Dark` and `Islands Darcula`; pass those quoted IDs explicitly alongside the seven classic IDs when reviewing that version.

For a faster review of connection-dialog spacing, explorer overflow, table editor/menu surfaces, auto-refresh sizing and active glyph colors, set `LATTICE_UI_STYLE_ONLY=true` (direct Gradle: `-Plattice.ui.styleOnly=true`). This still opens and edits the production UI inside the full IDE, then stops after those checks; omit it for the complete review.

Linux commit and release CI run the complete Dark-theme review followed by the focused Light-theme review on the pinned IDE. Java and script tests continue to run on Linux, Windows and macOS. The newer-IDE/theme override is available for local compatibility reviews.

```bash
LATTICE_UI_IDE_HOME=/path/to/idea-2026.2.3 ./scripts/review-intellij-ui.sh ExperimentalLight ExperimentalLightWithLightHeader IntelliJ JetBrainsLightTheme "Islands Light" ExperimentalDark Darcula JetBrainsHighContrastTheme "Islands Dark" "Islands Darcula"
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

`verify-plugin.py` accepts `--ide-home`, `--java-home`, `--cache` and `--reports`. It uses checksum-verified official Linux SDK archives for static bytecode analysis on any host; it does not execute their native launchers. Each run has a fresh report/scratch directory. A local SDK override avoids downloading. Gradle builds instead use the SDK matching the host.

Downloaded development assets use the OS user-cache directory: LocalAppData on Windows, Library/Caches on macOS, XDG_CACHE_HOME or ~/.cache on Linux. Set `LATTICE_DEV_CACHE` or pass a script's `--cache` override to relocate downloads. Build outputs remain in ignored `build/`.

The root `gradlew` / `gradlew.bat` are the standard Gradle wrapper launchers. Public helpers live in `scripts/`. Workstation-only runners belong in locally excluded `scripts/local/`, with private instructions in `docs/local/`; never force-add either directory. SDKs, legacy-server binaries and their asset-management scripts remain outside this repository and its history. See the [documentation index](README.md) for the project layout.
