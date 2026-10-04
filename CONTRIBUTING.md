# Contributing

Use JDK 21 and the committed Gradle wrapper. The compilation baseline is IntelliJ IDEA 2025.1; avoid APIs introduced after that baseline unless guarded and verified.

```bash
./gradlew test buildPlugin
python3 -m unittest discover -s scripts/tests -v
```

On Windows use `gradlew.bat` and `python`. Gradle downloads the SDK or accepts `-Plattice.ide.home=/path/to/idea-2025.1`. Test-only SDKs, database servers and driver caches belong outside the repository in `../intellij-extension-test-binaries/`, overridable with `LATTICE_TEST_BINARIES`. Set `JAVA_HOME` to JDK 21, `IDEA_HOME` for standalone scripts and `JAVA8_HOME` for legacy driver probes.

## Functional fixtures

The shared binaries directory's README lists the preserved local servers and startup commands. Ordinary suites require MySQL on localhost:3306 (`shop_db`, root, empty password) and HSQLDB on localhost:9001 (`testdb`, SA, empty password). Use isolated test servers: the tests create/drop temporary schemas and perform DDL and writes.

On Linux, the release workflow uses a MySQL 8.4 service container and `scripts/start-ci-hsqldb.sh` for an in-memory HSQLDB server. Locally, an equivalent MySQL container can be started with:

```bash
docker run --name lattice-mysql -d -p 127.0.0.1:3306:3306 \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_ROOT_HOST=% -e MYSQL_DATABASE=shop_db mysql:8.4
bash scripts/start-ci-hsqldb.sh
./gradlew integrationTest
```

Wait for MySQL readiness before the tests. Stop the container with `docker stop lattice-mysql`; stop the in-memory HSQLDB process whose PID is in `build/ci-fixtures/hsqldb.pid`. Persistent HSQLDB fixtures should be stopped with SQL `SHUTDOWN` so their data is checkpointed.

`test-functional.ps1` runs all JUnit suites plus a separate fallback-driver lifecycle process. `test-driver-compatibility.ps1` runs the legacy matrix with a Java 8 JDK; pass a hashtable of available MySQL version/port pairs. It owns and cleans up its temporary HSQLDB servers. Downloads go into the external cache; temporary output stays in `build/`.

## Changes and validation

Keep changes focused and add regression coverage for functional defects. Exercise both database dialects when changing JDBC behavior, DDL, metadata, conversion or exports. Run Plugin Verifier when using platform APIs:

```powershell
.\verify-idea-compatibility.ps1
```

Alternatively `./gradlew verifyPlugin` downloads the configured IDEs. The release workflow verifies the packaged candidate in separate jobs to keep disk usage bounded.

Explain the problem, resulting behavior, validation and remaining limits in a pull request. Add user-facing changes to `CHANGELOG.md`. Do not commit database data, SDKs, test installers, credentials, logs or generated distributions. SQL/log examples must use synthetic data and redact secrets.

AI-assisted contributions are welcome; contributors remain responsible for correctness and review. Lattice has used Gemini 3.8, GPT-6 Luna and GPT-6.1 Sol.

## Third-party dependencies

Production driver JARs in `lib/` support standalone packaging. Keep their versions aligned with `build.gradle.kts`, notices, license texts and source-asset metadata. Updating Connector/J requires updating and verifying the corresponding source archive/checksum in `scripts/prepare-source-asset.py`. See `THIRD_PARTY_NOTICES.md` and `RELEASING.md`.
