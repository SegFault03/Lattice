# Contributing

## Requirements

Use JDK 21, Git and the committed Gradle wrapper. Python 3.11+ supports the optional helper scripts; Python 3.12+ is recommended for SDK extraction. Linux/macOS MySQL fixtures require Docker; Windows defaults to portable native MySQL. No maintainer-specific folders or installed IDEs are required.

```text
python scripts/test.py --build
python -m unittest discover -s scripts/tests -v
```

On Linux/macOS you can also use `./gradlew test buildPlugin`; on Windows use `.\gradlew.bat test buildPlugin`. Set `JAVA_HOME` to JDK 21 or put Java on PATH, or pass `--java-home /path/to/jdk-21` to `scripts/test.py`. Gradle downloads the configured SDK. `--ide-home` / `-Plattice.ide.home` optionally select your own IntelliJ 2025.1 SDK.

## Database tests

```text
python scripts/test.py --live --mysql --hsqldb
```

The runner owns its MySQL 8.4 fixture and memory-only HSQLDB process, waits for readiness and stops both on exit. Each owned fixture uses an automatically selected free loopback port, printed at startup. `--mysql-port PORT` and `--hsqldb-port PORT` request fixed ports, which must be free. Windows jobs also stop fixture descendants when the runner is killed. Docker must be running when Docker fixtures are selected. Logs are in `build/fixtures/`.

To test against fixtures you manage yourself, omit `--mysql` and/or `--hsqldb`. Existing fixtures must be isolated and disposable: tests create/drop schemas and modify table data. They expect:

| Engine | Endpoint | Database | User | Password |
|---|---|---|---|---|
| MySQL | localhost:3306 | shop_db | root | empty |
| HSQLDB | localhost:9001 | testdb | SA | empty |

These are test fixture credentials, not recommendations for deployed servers. Use `python scripts/test.py --live` after starting equivalent fixtures; `--mysql-port` / `--hsqldb-port` override external server ports too. Direct Gradle runs use `LATTICE_TEST_MYSQL_PORT` / `LATTICE_TEST_HSQLDB_PORT` environment variables, defaulting to 3306/9001.

The Java 8/legacy-driver matrix is documented in [compatibility](compatibility.md) and is run by `python scripts/one-shot-test.py` on Linux and Windows. The runner copies supplied JDBC jars or downloads missing ones into an owned cache and executes the Java 21 service flow plus a Java 8 driver probe. Cleanup is controlled by `--cleanup` / `--no-cleanup`; Windows defers jar removal until the JVM exits. See [dependency paths](SCRIPTS.md#binary-paths-downloads-and-cleanup). `DatabaseCompatibilityTest` and `Java8DriverProbe` contain the reusable Java checks.

## Validation and changes

Run pure tests for every change. Run live suites for database behavior; check the release ZIP and Plugin Verifier for platform/dependency changes. Describe the trigger, changed behavior, validation and remaining limits in pull requests. The cross-platform workflow builds/tests on Windows, Linux and macOS; the release workflow additionally runs live fixtures and verifies three IntelliJ 2025 releases.

Use `python scripts/ui-preview.py` for fast Swing previews. For real-IDE layout and action flows, use `./scripts/linux/review-intellij-ui.sh` on Linux/X11 or `.\scripts\windows\review-intellij-ui.ps1` on Windows; static compatibility does not establish those behaviors. Dynamic unload and project-close testing remain separate checks. Never commit passwords, connection state, user databases, generated outputs, local SDKs or native test binaries.

## Dependencies and packaging

Gradle resolves packaged production dependencies from Maven Central and stages fallback-test drivers under ignored `build/fallback-drivers/`. JDBC JARs are not tracked. Keep dependency versions aligned with `build.gradle.kts`, notices, license texts and source-asset metadata. A Connector/J upgrade also requires updating its pinned corresponding-source URL/checksum in `scripts/prepare-source-asset.py`.

`assets/icon.png` is documentation branding. IntelliJ loads its plugin/tool-window icons from `src/main/resources/META-INF/` and `src/main/resources/icons/`; retain those paths. See [RELEASING](RELEASING.md).
