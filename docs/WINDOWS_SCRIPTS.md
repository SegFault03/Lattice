# Running Windows scripts locally

All public Windows entry points live in `scripts/windows/`. Common Python commands stay in `scripts/`; Linux Bash commands live in `scripts/linux/`. Run these commands from PowerShell in the repository root. Absolute launcher paths also work from another directory. Python 3.11+ must be installed; PowerShell 5.1 or later is supported. Java, IDEs, fixture servers and required JARs are reused or downloaded as needed.

```powershell
Set-Location 'C:\path\to\Lattice'
$assets = 'C:\path\to\test-binaries'
$version = (Select-String -Path gradle.properties -Pattern '^pluginVersion=').Line.Split('=', 2)[1]
$zip = "build/distributions/Lattice-$version.zip"
$deps = @('--binaries-dir', $assets, '--download-dir', "$assets\downloads",
          '--gradle-user-home', "$env:USERPROFILE\.gradle", '--cleanup')
```

If execution policy blocks a launcher, invoke it explicitly with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\test.ps1 --help`. This changes only that process. Supply `-Python C:\path\to\python.exe` before the Python-style arguments to select an interpreter. `--help` lists the current complete argument set for the nine Python launchers.

## Shared dependency options

| Option | Meaning |
|---|---|
| `--binaries-dir PATH` | Existing reusable assets; omitted inputs are searched here. An invalid supplied directory fails. |
| `--download-dir PATH` | Parent for an owned lattice-run-* workspace. Default: OS user cache or LATTICE_DEV_CACHE. |
| `--gradle-user-home PATH` | Reuse a Gradle distribution/Maven cache; takes precedence over GRADLE_USER_HOME. With neither configured, the script creates a run-owned cache. Builds always use the committed wrapper. |
| `--cleanup` | Delete only the owned downloaded/scratch workspace, including after failure. Explicit assets, shared Gradle cache and final reports remain. |
| `--no-cleanup` | Keep downloaded/scratch assets for later reuse. Owned servers are still stopped. |
| `-Python PATH` | PowerShell launcher option selecting Python (default: python). |

`test`, `one-shot-test` and `probe-hsqldb-cancellation` clean up by default; other commands retain downloads by default. The archive checker has only its listed file options. Invalid explicit binary paths fail instead of silently downloading replacements. Each section below includes every Python option exactly as reported by --help; `-Python` is the additional launcher option.

Helpers add `-Djava.net.preferIPv4Stack=true` to `JAVA_TOOL_OPTIONS` before Java starts, including Gradle distribution downloads, to avoid Windows dual-stack socket timeouts. Other Java options are preserved. If you explicitly set `-Djava.net.preferIPv4Stack=`, your value takes precedence.

Replace the example checkout and asset paths with your own locations. Use `$env:USERPROFILE` in PowerShell and `%USERPROFILE%` in CMD. Dependency/cache paths expand variables; unresolved variables fail before creating directories.

Temporary Python, shell, Gradle, Java, verifier and fixture command trees are owned by Windows jobs. They are stopped on completion, failure, timeout or interruption, including if the PowerShell parent is killed. File retention with `--no-cleanup` does not retain processes. The IDEA instance deliberately reopened by `dev-deploy` stays open for use.

The reusable asset tree contains ides/<version>/, mysql/<version>/, runtimes/java-8/, runtimes/vc2010/, runtimes/vc2013/, jdbc/mysql/, jdbc/hsqldb/, tools/junit/, tools/flatlaf/, tools/7zip/, tools/verifier/ and sources/. MySQL homes can contain a nested extracted server. Java homes are extracted runtimes/JDKs; IDE homes contain product-info.json. The Java 21 JDK in ides/2025.1/jbr is suitable for compilation; ides/2026.2.3 has a newer JBR and is for additional UI reviews.

## test.ps1

Run Gradle unit tests, optional packaging and live database/fallback-driver checks.

```powershell
.\scripts\windows\test.ps1 --live --mysql --hsqldb --build @deps
```

`--hsqldb` and `--mysql` require `--live`; `--integration-only` requires `--live` and cannot be combined with `--build`. Windows uses native MySQL 8.4.2 unless `--docker` selects a running Docker daemon. The run initializes disposable data and stops its own server trees, including if the runner is killed. Owned fixtures choose free loopback ports automatically and print them at startup. `--mysql-port PORT` / `--hsqldb-port PORT` request fixed ports, which must be free; `0` requests automatic selection. Without the corresponding fixture flag, these options select an external test server (defaults 3306/9001). `--version` and `--notes-file` override release metadata; `--hsqldb-jar` selects the fixture driver.

Complete options (forwarded unchanged to Python):

```text
usage: test.py [-h] [--live] [--hsqldb] [--mysql] [--build]
               [--integration-only] [--ide-home IDE_HOME]
               [--java-home JAVA_HOME] [--version VERSION]
               [--notes-file NOTES_FILE] [--mysql-home MYSQL_HOME]
               [--docker DOCKER] [--runtime-dir RUNTIME_DIR]
               [--sevenzip SEVENZIP] [--hsqldb-jar HSQLDB_JAR]
               [--mysql-port MYSQL_PORT] [--hsqldb-port HSQLDB_PORT]
               [--download-dir DOWNLOAD_DIR] [--cleanup] [--no-cleanup]
               [--binaries-dir BINARIES_DIR]
               [--gradle-user-home GRADLE_USER_HOME]

Run Gradle checks with optional owned, disposable database fixtures.

options:
  -h, --help            show this help message and exit
  --live                Run integration and fallback-driver checks
  --hsqldb              Own an in-memory HSQLDB fixture for this run
  --mysql               Own a MySQL fixture (native 8.4.2 on Windows; Docker
                        8.4 on Linux/macOS)
  --build               Also package the plugin ZIP
  --integration-only    With --live, run only the integration and fallback-
                        driver suites
  --ide-home IDE_HOME   Optional local IntelliJ 2025.1 SDK; otherwise Gradle
                        downloads it
  --java-home JAVA_HOME
                        JDK 21 directory; otherwise use JAVA_HOME or PATH
  --version VERSION
  --notes-file NOTES_FILE
  --mysql-home MYSQL_HOME
                        Native Windows MySQL 8.4.2 home or mysqld.exe;
                        otherwise download missing fixture
  --docker DOCKER       Docker CLI path; selects Docker fixtures on Windows
  --runtime-dir RUNTIME_DIR
                        Windows VC runtime DLL directory
  --sevenzip SEVENZIP   7z.exe/7za.exe for missing Windows runtime extraction
  --hsqldb-jar HSQLDB_JAR
                        HSQLDB fixture JAR; otherwise find/download HSQLDB
                        2.7.4
  --mysql-port MYSQL_PORT
                        Owned fixture port (default: automatic); without
                        --mysql use external port 3306
  --hsqldb-port HSQLDB_PORT
                        Owned fixture port (default: automatic); without
                        --hsqldb use external port 9001
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## one-shot-test.ps1

Run isolated tooling, build, live fixtures, three IDE verifier checks and the full JDBC/Java 8 matrix.

```powershell
.\scripts\windows\one-shot-test.ps1 @deps
```

The three mode flags are mutually exclusive: `--skip-compatibility` runs routine build/live checks; `--compatibility-only` runs packaging, IDE verification and database matrices; `--database-compatibility-only` runs just the database matrices. Repeat `--ide-sdk VERSION=PATH` for 2025.1/2025.2/2025.3 and `--mysql-home VERSION=PATH` for 5.5.62/5.6.51/5.7.44/8.4.2. `--jdbc-dir` contains mysql/ and hsqldb/ subfolders; `--java8-home` is the legacy runtime. Failures save logs under build/one-shot-test/. Fixtures choose free loopback ports automatically, including each matrix server. `--mysql-port PORT` / `--hsqldb-port PORT` request fixed free ports for MySQL and the live HSQLDB fixture. Allow at least 10 GiB free workspace space.

Complete options (forwarded unchanged to Python):

```text
usage: one-shot-test.py [-h] [--java-home JAVA_HOME]
                        [--skip-compatibility | --compatibility-only | --database-compatibility-only]
                        [--java8-home JAVA8_HOME] [--ide-home IDE_HOME]
                        [--ide-sdk VERSION=PATH] [--mysql-home VERSION=PATH]
                        [--verifier-jar VERIFIER_JAR] [--jdbc-dir JDBC_DIR]
                        [--runtime-dir RUNTIME_DIR] [--sevenzip SEVENZIP]
                        [--docker DOCKER] [--mysql-port MYSQL_PORT]
                        [--hsqldb-port HSQLDB_PORT]
                        [--download-dir DOWNLOAD_DIR] [--cleanup]
                        [--no-cleanup] [--binaries-dir BINARIES_DIR]
                        [--gradle-user-home GRADLE_USER_HOME]

Run Lattice's headless Linux/Windows validation in disposable tool/cache
directories. The runner checks tooling, Java unit tests, package integrity,
live database behavior, all supported IntelliJ targets, and the documented
Java 8/JDBC compatibility matrix. It never starts an IDE; use review-intellij-
ui.sh for real UI validation. Downloaded assets are removed by default; --no-
cleanup retains them for reuse.

options:
  -h, --help            show this help message and exit
  --java-home JAVA_HOME
                        Use this full JDK 21; otherwise use JAVA_HOME/PATH,
                        then download a temporary JDK 21
  --skip-compatibility  Run tooling, build and live functional tests only;
                        real IDE UI checks run separately
  --compatibility-only  Run the IDE verifier and JDBC compatibility matrices;
                        build the plugin but skip ordinary tests
  --database-compatibility-only
                        Run only the JDBC/Java 8 matrix; useful when Plugin
                        Verifier runs separately
  --java8-home JAVA8_HOME
                        Java 8 runtime; download if missing
  --ide-home IDE_HOME   Build baseline IntelliJ 2025.1 home
  --ide-sdk VERSION=PATH
                        Repeat for each verification SDK
  --mysql-home VERSION=PATH
                        Repeat for each native MySQL server
  --verifier-jar VERIFIER_JAR
  --jdbc-dir JDBC_DIR   Existing mysql/ and hsqldb/ driver folders (copied
                        into disposable cache)
  --runtime-dir RUNTIME_DIR
  --sevenzip SEVENZIP
  --docker DOCKER       Docker CLI; selects Docker on Windows (daemon must
                        already be running)
  --mysql-port MYSQL_PORT
                        Owned MySQL fixture port; default: automatic free port
                        for each server
  --hsqldb-port HSQLDB_PORT
                        Owned live HSQLDB fixture port; default: automatic
                        free port
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## ui-preview.ps1

Render the production Swing UI without opening an IDE; refresh the five tracked README screenshots.

```powershell
.\scripts\windows\ui-preview.ps1 --all-previews @deps
```

`--all-previews` renders a light/dark gallery. `--output` defaults to build/ui-preview/; `--compare-with` points to an earlier gallery for comparisons. Gallery HTML/ZIP files are ignored. Every successful run refreshes the five images under screenshots/; review their Git diff. JUnit 4.13.2, Hamcrest Core 1.3 and FlatLaf 3.7.2 can be supplied individually.

Complete options (forwarded unchanged to Python):

```text
usage: ui-preview.py [-h] [--ide-home IDE_HOME] [--java-home JAVA_HOME]
                     [--compare-with COMPARE_WITH] [--output OUTPUT]
                     [--all-previews] [--flatlaf-jar FLATLAF_JAR]
                     [--junit-jar JUNIT_JAR] [--hamcrest-jar HAMCREST_JAR]
                     [--download-dir DOWNLOAD_DIR] [--cleanup] [--no-cleanup]
                     [--binaries-dir BINARIES_DIR]
                     [--gradle-user-home GRADLE_USER_HOME]

Render database UI components without launching an IntelliJ IDE window.
Requires JDK 21 and an IntelliJ 2025.1 SDK (Gradle's cached SDK is
discovered). The IntelliJ test application supplies native action-system
services; preview-only service shadows block database work and FlatLaf
approximates IDE themes. Refreshes the five featured screenshots/ PNGs and a
local gallery under ignored build/ui-preview/.

options:
  -h, --help            show this help message and exit
  --ide-home IDE_HOME   IntelliJ SDK installation directory
  --java-home JAVA_HOME
                        JDK 21 installation directory
  --compare-with COMPARE_WITH
                        Previous output directory for before/after comparison
  --output OUTPUT       Gallery/build directory; screenshots/ keeps only the
                        five featured images
  --all-previews        Generate the full light/dark preview gallery; by
                        default only the five featured screens are rendered
  --flatlaf-jar FLATLAF_JAR
                        Existing FlatLaf 3.7.2 JAR
  --junit-jar JUNIT_JAR
                        Existing JUnit 4.13.2 JAR
  --hamcrest-jar HAMCREST_JAR
                        Existing Hamcrest Core 1.3 JAR
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## dev-deploy.ps1

Validate/build the plugin, deploy into an installed IDEA and reopen it if it was running.

```powershell
.\scripts\windows\dev-deploy.ps1 --dry-run --ide-home "$assets\ides\2025.1" @deps
```

Remove `--dry-run` to deploy. `--list-ides` lists detected installations. These inspection modes do not download/build/deploy. `--ide-home` selects the deployment target; `--build-ide-home` selects the 2025.1 compilation SDK. `--plugins-dir` selects a custom user plugin directory. `--project` chooses what opens after restart. `--shutdown-timeout` defaults to 120 seconds, `--startup-timeout` to 60. Save IDE work before deployment: the script requests a graceful shutdown and waits for save prompts. Only Lattice is replaced; a failed replacement rolls back. `--no-color` disables terminal colors.

Complete options (forwarded unchanged to Python):

```text
usage: dev-deploy.py [-h] [--ide-home IDE_HOME] [--plugins-dir PLUGINS_DIR]
                     [--java-home JAVA_HOME] [--build-ide-home BUILD_IDE_HOME]
                     [--download-dir DOWNLOAD_DIR] [--cleanup] [--no-cleanup]
                     [--binaries-dir BINARIES_DIR]
                     [--gradle-user-home GRADLE_USER_HOME] [--project PROJECT]
                     [--dry-run] [--list-ides] [--shutdown-timeout SECONDS]
                     [--startup-timeout SECONDS] [--no-color]

Test, build and deploy Lattice to a local IDEA installation using standard-
library tools.

options:
  -h, --help            show this help message and exit
  --ide-home IDE_HOME   Installed IDEA directory or macOS .app; otherwise
                        discover it
  --plugins-dir PLUGINS_DIR
                        Explicit custom user plugins directory
  --java-home JAVA_HOME
                        JDK 21 directory; otherwise discover Java 21
  --build-ide-home BUILD_IDE_HOME
                        Local build SDK; deployment target still comes from
                        --ide-home/discovery
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
  --project PROJECT     Project to open after restart (otherwise IDEA's reopen
                        setting applies)
  --dry-run             Print targets and planned steps without building,
                        deploying or restarting
  --list-ides           List discovered compatible IDEA installations and exit
  --shutdown-timeout SECONDS
  --startup-timeout SECONDS
  --no-color            Disable ANSI colors (also honors NO_COLOR)

Save IDE work before deploying. No force-kill is used.
```

## verify-plugin.ps1

Run JetBrains Plugin Verifier against one plugin ZIP and one IntelliJ SDK.

```powershell
.\scripts\windows\verify-plugin.ps1 $zip --ide-version 2025.1 @deps
```

The ZIP is a required positional argument. `--ide-version` is 2025.1 (default), 2025.2 or 2025.3. `--ide-home` supplies its extracted SDK, and `--verifier-jar` supplies checksum-pinned Verifier 1.410. `--reports` defaults to build/compatibility/verifier-ci/; each invocation gets a unique report directory. `--cache` selects a legacy static SDK/verifier cache; prefer shared dependency flags for new commands.

Complete options (forwarded unchanged to Python):

```text
usage: verify-plugin.py [-h] [--ide-version {2025.1,2025.2,2025.3}]
                        [--ide-home IDE_HOME] [--java-home JAVA_HOME]
                        [--cache CACHE] [--verifier-jar VERIFIER_JAR]
                        [--download-dir DOWNLOAD_DIR] [--cleanup]
                        [--no-cleanup] [--binaries-dir BINARIES_DIR]
                        [--gradle-user-home GRADLE_USER_HOME]
                        [--reports REPORTS]
                        archive

positional arguments:
  archive

options:
  -h, --help            show this help message and exit
  --ide-version {2025.1,2025.2,2025.3}
  --ide-home IDE_HOME
  --java-home JAVA_HOME
  --cache CACHE
  --verifier-jar VERIFIER_JAR
                        Local checksum-pinned Plugin Verifier 1.410 JAR
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
  --reports REPORTS
```

## check-release-archive.ps1

Validate release ZIP layout, plugin descriptor, licenses and Java 21 bytecode; optionally write SHA256SUMS.

```powershell
.\scripts\windows\check-release-archive.ps1 $zip --version $version --source "$assets\sources\mysql-connector-j-26.7.0-source.tar.gz" --checksums build/release/SHA256SUMS
```

The ZIP and `--version` are required. `--source` adds the source archive to the checksums list; `--checksums` names the output file. This command uses only Python and input files, so it has no dependency/download/cleanup flags. It does not build the ZIP.

Complete options (forwarded unchanged to Python):

```text
usage: check-release-archive.py [-h] --version VERSION [--source SOURCE]
                                [--checksums CHECKSUMS]
                                archive

positional arguments:
  archive

options:
  -h, --help            show this help message and exit
  --version VERSION
  --source SOURCE
  --checksums CHECKSUMS
```

## prepare-source-asset.ps1

Retrieve or reuse the checksum-pinned Connector/J 26.7.0 corresponding source archive.

```powershell
.\scripts\windows\prepare-source-asset.ps1 --source-archive "$assets\sources\mysql-connector-j-26.7.0-source.tar.gz" @deps
```

`--output` defaults to build/release/. `--source-archive` supplies an existing archive; `--cache` selects a legacy source cache. `--github-output` appends its filename to an Actions output file when explicitly requested. The output archive is retained even with cleanup enabled.

Complete options (forwarded unchanged to Python):

```text
usage: prepare-source-asset.py [-h] [--output OUTPUT] [--cache CACHE]
                               [--github-output GITHUB_OUTPUT]
                               [--source-archive SOURCE_ARCHIVE]
                               [--download-dir DOWNLOAD_DIR] [--cleanup]
                               [--no-cleanup] [--binaries-dir BINARIES_DIR]
                               [--gradle-user-home GRADLE_USER_HOME]

options:
  -h, --help            show this help message and exit
  --output OUTPUT
  --cache CACHE
  --github-output GITHUB_OUTPUT
                        Append the prepared filename to this Actions output
                        file
  --source-archive SOURCE_ARCHIVE
                        Existing checksum-pinned Connector/J 26.7.0 source
                        archive
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## release.ps1

Validate stable/prerelease version metadata and generate release notes from Git history.

```powershell
.\scripts\windows\release.ps1 --version $version @deps
```

`--version` overrides pluginVersion in gradle.properties; `--tag` checks release tag metadata. `--root` defaults to this repository and `--output` to build/release/. `--git` selects an executable; portable MinGit is downloaded on Windows if needed. The command writes release-notes.md, patch-notes.html and version.txt. It does not commit, tag, push or publish.

Complete options (forwarded unchanged to Python):

```text
usage: release.py [-h] [--root ROOT] [--tag TAG] [--version VERSION]
                  [--output OUTPUT] [--git GIT] [--download-dir DOWNLOAD_DIR]
                  [--cleanup] [--no-cleanup] [--binaries-dir BINARIES_DIR]
                  [--gradle-user-home GRADLE_USER_HOME]

options:
  -h, --help            show this help message and exit
  --root ROOT
  --tag TAG
  --version VERSION
  --output OUTPUT
  --git GIT             Git executable; otherwise PATH, then portable MinGit
                        on Windows
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## probe-hsqldb-cancellation.ps1

Run one isolated in-memory HSQLDB cancellation diagnostic and check connection usability after cancellation.

```powershell
.\scripts\windows\probe-hsqldb-cancellation.ps1 --timeout 45 @deps
```

An optional positional driver_jar selects the HSQLDB JAR; otherwise 2.7.4 is reused/downloaded. `--timeout` defaults to 45 seconds and must be positive. A timeout terminates the owned JVM and returns exit code 124. This diagnostic needs no external database server.

Complete options (forwarded unchanged to Python):

```text
usage: probe-hsqldb-cancellation.py [-h] [--java-home JAVA_HOME]
                                    [--timeout TIMEOUT]
                                    [--download-dir DOWNLOAD_DIR] [--cleanup]
                                    [--no-cleanup]
                                    [--binaries-dir BINARIES_DIR]
                                    [--gradle-user-home GRADLE_USER_HOME]
                                    [driver_jar]

Run the in-memory HSQLDB cancellation diagnostic with a bounded JVM lifetime.

positional arguments:
  driver_jar            HSQLDB driver JAR; find/download 2.7.4 when omitted

options:
  -h, --help            show this help message and exit
  --java-home JAVA_HOME
  --timeout TIMEOUT
  --download-dir DOWNLOAD_DIR
                        Download/cache parent; only directories created by
                        this run are cleaned
  --cleanup             Remove this run's downloaded dependencies and scratch
                        files, including on failure
  --no-cleanup          Keep downloaded dependencies and scratch files for
                        reuse
  --binaries-dir BINARIES_DIR
                        Optional existing asset collection (ides/, mysql/,
                        jdbc/, tools/)
  --gradle-user-home GRADLE_USER_HOME
                        Reuse this Gradle cache; otherwise honor
                        GRADLE_USER_HOME or use an owned cache
```

## capture-intellij-ui.ps1

Build/load the plugin in a real test IDEA and capture its tool window. This uses an interactive Windows desktop with a primary display of at least 1440×800. Keep the test IDE visible and in the foreground.

```powershell
.\scripts\windows\capture-intellij-ui.ps1 -BinariesDir $assets `
    -DownloadDir "$assets\downloads" -GradleUserHome "$env:USERPROFILE\.gradle" `
    -Theme 'ExperimentalDark' -Output 'build/ui-test-results' -Cleanup
```

| Option | Meaning/default |
|---|---|
| `-JavaHome PATH` | Full JDK 21; discover/download when omitted. |
| `-IdeHome PATH` | Extracted IDEA to launch; default LATTICE_UI_IDE_HOME, then discover/download 2025.1. |
| `-BuildIdeHome PATH` | 2025.1 SDK used for compilation when launching another IDEA version. |
| `-Theme ID` | Theme to use; default ExperimentalDark. |
| `-Output PATH` | Screenshot/evidence directory; default build/ui-test-results/. |
| `-Review` | Exercise complete review flows as well as capture. |
| `-InputsOnly` | Focus on connection-dialog input alignment; also accepts LATTICE_UI_INPUTS_ONLY=true. |
| `-StyleOnly` | Focus on styling, toolbar/action glyphs and editing surfaces; also accepts LATTICE_UI_STYLE_ONLY=true. |
| `-GradleArgs @(...)` | Additional individual Gradle arguments, e.g. @("--stacktrace"). |
| `-BinariesDir PATH` | Reusable asset collection. |
| `-DownloadDir PATH` | Owned dependency workspace parent. |
| `-GradleUserHome PATH` | Shared Gradle/Maven cache. |
| `-Cleanup` / `-NoCleanup` | Delete/retain owned dependency scratch; retention is default. Choose one. |
| `-Python PATH` | Python interpreter; default python. |

`-DependenciesReady` is an internal recursive-bootstrap flag and should be omitted by callers. PowerShell common parameters such as `-Verbose` are accepted, but normal diagnostics come from Gradle. Final screenshots/logs remain under build/ even with -Cleanup.

## review-intellij-ui.ps1

Run real IDE capture/review flows for each selected bundled theme, prepare the JDBC Maven fixtures, then write screenshots, logs, JUnit results and an index under build/ui-review/. It uses the same interactive-desktop requirements as capture.

```powershell
.\scripts\windows\review-intellij-ui.ps1 -BinariesDir $assets `
    -DownloadDir "$assets\downloads" -GradleUserHome "$env:USERPROFILE\.gradle" `
    -Themes @('ExperimentalDark', 'ExperimentalLight') -StyleOnly -Cleanup

# Review the additional supplied IDEA while compiling against the baseline:
.\scripts\windows\review-intellij-ui.ps1 -BinariesDir $assets `
    -IdeHome "$assets\ides\2026.2.3" -BuildIdeHome "$assets\ides\2025.1" `
    -Themes @('Islands Light', 'Islands Dark') -StyleOnly -Cleanup
```

| Option | Meaning/default |
|---|---|
| `-Themes @(...)` | Selected themes; default is all seven classic themes listed below. May also be the first positional argument. |
| `-OutputRoot PATH` | Result/index parent; default LATTICE_UI_REVIEW_OUTPUT or build/ui-review/. |
| `-FixtureCache PATH` | Existing mysql/ and hsqldb/ JDBC directories; default LATTICE_UI_FIXTURE_CACHE or BinariesDir/jdbc. Missing required JARs are downloaded. |
| `-MavenRepository PATH` | Reusable Maven-layout fixture repository; otherwise an owned temporary repository is prepared. |
| `-InputsOnly` / `-StyleOnly` | Focused review modes as described for capture. |
| `-JavaHome`, `-IdeHome`, `-BuildIdeHome` | Same JDK/SDK selection as capture. Java 21 is used for compilation. |
| `-BinariesDir`, `-DownloadDir`, `-GradleUserHome` | Same dependency/cache options as capture. |
| `-Cleanup` / `-NoCleanup` | Delete/retain owned dependencies; retention is default. Choose one. |
| `-Python PATH` | Python interpreter; default python. |

Default themes: ExperimentalDark, ExperimentalLight, ExperimentalLightWithLightHeader, JetBrainsHighContrastTheme, Darcula, IntelliJ, JetBrainsLightTheme. Islands Dark, Islands Light and Islands Darcula require an IDEA version that supplies them, such as the supplied 2026.2.3. `-DependenciesReady` is internal. Review does not accept capture's -Theme, -Output, -Review or -GradleArgs options.

## Internal files and generated outputs

`windows-common.ps1`, `windows-script-dependencies.py` and `native_mysql.py` are internal helpers, not additional public commands. The first serializes native PowerShell arguments; the second prepares the JDK/SDK/JDBC inputs, launches the UI script and owns cleanup; the third initializes and stops portable MySQL fixtures. There are no workstation-specific paths in public scripts.

Only the five README PNGs under screenshots/ are tracked generated artifacts. Other screenshots, archives, JDBC/server/IDE binaries and reports are ignored. Authored branding/plugin icons and the committed Gradle wrapper JAR remain source infrastructure. The fallback-driver test stages Maven-resolved JDBC drivers under build/fallback-drivers/; a checked-in lib/ directory is unnecessary.

## Validation of the consolidated layout

The Windows validation passed 81 Python tooling tests, Gradle unit and live MySQL/HSQLDB tests, fallback-driver loading/disposal, plugin packaging and archive checks, all four trimmed MySQL initializers, the HSQLDB cancellation diagnostic, UI test compilation and 106 Swing gallery variants. Every Python PowerShell launcher was exercised from another directory with both the default interpreter and an explicit -Python. PowerShell parsing and Bash syntax checks passed. The five README images were preserved unchanged. Validation build/cache outputs were removed afterward.

Real interactive IDE capture/review was not rerun during this cleanup. Those commands need the test IDE to stay in the foreground; the earlier attempt was interrupted by another foreground application.
