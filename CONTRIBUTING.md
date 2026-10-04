# Contributing

## Requirements

Use JDK 21, Git and the committed Gradle wrapper. Python 3.11+ supports the optional helper scripts; Python 3.12+ is recommended for SDK extraction. Docker is needed only when asking the runner to start a MySQL fixture. No maintainer-specific folders or installed IDEs are required.

```text
python scripts/test.py --build
python -m unittest discover -s scripts/tests -v
```

On Linux/macOS you can also use `./gradlew test buildPlugin`; on Windows use `.\gradlew.bat test buildPlugin`. Set `JAVA_HOME` to JDK 21 or put Java on PATH. Gradle downloads the configured SDK. `--ide-home` / `-Plattice.ide.home` optionally select your own IntelliJ 2025.1 SDK.

## Database tests

```text
python scripts/test.py --live --mysql --hsqldb
```

The runner owns its MySQL 8.4 Docker container and memory-only HSQLDB process, waits for readiness and stops both on exit. Published ports are bound to loopback. Logs are in `build/fixtures/`. Docker must be running and ports 3306/9001 free.

To test against fixtures you manage yourself, omit `--mysql` and/or `--hsqldb`. Existing fixtures must be isolated and disposable: tests create/drop schemas and modify table data. They expect:

| Engine | Endpoint | Database | User | Password |
|---|---|---|---|---|
| MySQL | localhost:3306 | shop_db | root | empty |
| HSQLDB | localhost:9001 | testdb | SA | empty |

These are test fixture credentials, not recommendations for deployed servers. Use `python scripts/test.py --live` or Gradle `test integrationTest fallbackDriverTest` after starting equivalent fixtures.

The historical Java 8/legacy-driver matrix is documented in [compatibility](docs/compatibility.md). Its environment-specific orchestration is not part of the public repository; `DatabaseCompatibilityTest` and `Java8DriverProbe` retain the reusable Java checks.

## Validation and changes

Run pure tests for every change. Run live suites for database behavior; check the release ZIP and Plugin Verifier for platform/dependency changes. Describe the trigger, changed behavior, validation and remaining limits in pull requests. The cross-platform workflow builds/tests on Windows, Linux and macOS; the release workflow additionally runs live fixtures and verifies three IntelliJ 2025 releases.

Interactive IDE layout, actions, dynamic unload and project-close testing are separate checks; static compatibility does not establish those behaviors. Never commit passwords, connection state, user databases, generated outputs, local SDKs or native test binaries.

## Dependencies and packaging

Production JARs in `lib/` support fallback-driver tests and carry no platform-native executables. Gradle resolves packaged production dependencies from Maven Central. Keep bundled versions aligned with `build.gradle.kts`, notices, license texts and source-asset metadata. A Connector/J upgrade also requires updating its pinned corresponding-source URL/checksum in `scripts/prepare-source-asset.py`.

`assets/icon.png` is documentation branding. IntelliJ loads its plugin/tool-window icons from `src/main/resources/META-INF/` and `src/main/resources/icons/`; retain those paths. See [RELEASING](RELEASING.md).
