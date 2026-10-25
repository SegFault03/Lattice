# H2 validation

Run the complete H2 flow on Linux with JDK 21, Xvfb and XFWM (the CI window manager):

```bash
python scripts/h2-test.py --java-home /path/to/jdk21
```

The runner runs ordinary tests, owned TCP/TLS functional tests, builds and checks
the release ZIP, runs the driver/runtime matrix, and exercises the complete
production UI in both ExperimentalDark and ExperimentalLight. It exits on the
first failed phase and writes `build/h2-validation/completed.txt` only after all
selected phases pass. Dependencies are downloaded when missing. Supply
`--java8-home`, `--ide-home` and `--gradle-user-home` to reuse local tools.

For focused work:

```bash
./gradlew test h2IntegrationTest h2FallbackDriverTest buildPlugin
python scripts/h2-compatibility.py --java-home /path/to/jdk21 --java8-home /path/to/java8
python scripts/h2-test.py --ui-only --java-home /path/to/jdk21
```

`--skip-ui` supports headless Windows/macOS runs; `--skip-compatibility` explicitly
omits the downloadable matrix. These flags are recorded in the completion marker
and do not claim that the omitted phases passed. `--themes` selects UI themes;
without it, both dark and light run the complete scenario. An existing `DISPLAY`
is supported; otherwise the Linux capture script starts and cleans up Xvfb.

## Database suites

| Suite | Coverage |
|---|---|
| `H2ProviderTest`, `H2FeatureTest` | URL/default/driver rules, existing `.mv.db` paths, file persistence, identity/generated/temporal metadata, native DDL replay and isolated driver disposal |
| `ProviderEndToEndTest.embeddedH2Workflow` | Shared service contract: schema/table operations, metadata, row insert/update/delete, failed-batch rollback, pagination, export/reimport, console namespace and table alterations |
| `H2RegressionTest` | Unicode, quotes, binary/temporal/decimal/boolean/NULL values; CSV/JSON/SQL exports and SQL replay; identity state, computed columns, constraints, indexes and view DDL replay; rejection of batches inside existing transactions; failed alterations; cancellation races, timeouts and session recovery |
| `H2FunctionalIntegrationTest` / `h2IntegrationTest` | Reuses the production and regression flows over file, standard TCP URLs and TLS; validates failed authentication redaction and subsequent recovery |
| `h2FallbackDriverTest` | A separate JVM without JDBC JARs on its parent classpath loads the packaged H2 driver, queries, verifies caching, deregisters and disposes it |
| `H2CompatibilityTest` | Downloads validated, isolated H2 drivers and checks selected artifacts and service workflows; embedded/mode cases own full regressions |

TCP and TLS fixtures use temporary databases and free ports, and stop their owned
servers. The TLS test generates a temporary certificate with the JDK's keytool;
only the test JVM's temporary trust configuration changes. Existing database
servers, IDE profiles and host trust stores are not modified.

The shared H2 regression flow also alters timestamp nullability while retaining
its ON UPDATE expression, then changes an identity column's type and checks
GENERATED ALWAYS enforcement, the next generated value and its increment.
These checks run for embedded and bundled TCP/TLS, and for selected embedded drivers and compatibility modes. File and selected TCP matrix cases retain the provider workflow without repeating the full regression suite.
Column modifications use targeted ALTER operations rather than replacing the
complete definition, so unrelated update expressions and identity options survive.

## Driver/runtime matrix

The matrix uses drivers and servers **2.1.214**, **2.2.224** and **2.3.232**:

- Six embedded cases: memory and file mode with each selected driver.
- Five TCP cases: each matching driver/server, plus oldest/newest in both directions.
- Six mode cases: MySQL and PostgreSQL compatibility modes with each driver.
- A negative runtime check: 2.3.232 must reject Java 8 with `UnsupportedClassVersionError`.

2.1.214 and 2.2.224 run both as Java 8 probes/servers and as isolated drivers in the
Java 21 plugin JVM. 2.3.232 has Java 11 bytecode and runs on Java 21; it cannot run
on Java 8. The matrix reports the actual JVM versions. It does not test Java 11
itself, H2 1.x migration, arbitrary third-party types or all compatibility-mode SQL.

Results are in `build/compatibility/h2/results.txt` for the matrix-only command or
`build/h2-validation/compatibility/results.txt` for the complete runner, alongside
server logs and owned file fixtures.

## Real IntelliJ UI

The existing full real-IDE scenario uses an owned H2 TCP server behind a response
gate. It selects the bundled H2 driver in the production connection dialog,
checks memory/file/server forms and validation, tests and persists a connection,
browses and changes schemas/tables, validates and commits grid edits, handles
failed commits, reverts and filters, inspects CREATE TABLE, saves CSV/JSON/SQL
exports, and runs/cancels/recovers console queries. The gate exposes transient
busy states using real JDBC responses.

Each theme saves full desktop screenshots, `runtime-evidence.txt` and its JUnit
result under `build/h2-validation/ui/`. UI checks assert component state and
persisted data; screenshots are supporting evidence.

H2 embedded functional checks and plugin packaging run on every branch. Only
PRs targeting `main` run TCP/TLS suites on all three host platforms, one dark provider UI flow
on Linux and the H2 compatibility matrix. Tag releases run functional/build
checks before signing. See [CI test groups](CI_TESTING.md).

## Metadata boundary

In H2 PostgreSQL compatibility mode, aliased SQL columns may report their alias
as the source name and omit the source table. Lattice leaves optional key badges
empty when the driver does not identify the source column. Unaliased columns and
table-editor columns retain their available key metadata.
