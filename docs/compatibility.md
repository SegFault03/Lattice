# IntelliJ and database compatibility

## IntelliJ baseline

Production targets Java 21 and IntelliJ IDEA 2025.1 (build 251). Plugin Verifier 1.410 checked the packaged ZIP against full SDKs without ignored compatibility errors:

The build-tool baseline is Gradle 9.8.0 through the checksum-pinned wrapper and IntelliJ Platform Gradle Plugin 2.19.0. Pure tests, plugin packaging, UI previews, live MySQL/HSQLDB/PostgreSQL flows, the Java 8 JDBC probes and the IntelliJ matrix are validated separately so a dependency change can be checked against each layer.

| IDEA | Build | Result |
|---|---|---|
| 2025.1 Community | 251.23774.435 | Compatible |
| 2025.2 Community | 252.23892.409 | Compatible |
| 2025.3 Unified/Ultimate | 253.28294.334 | Compatible |

Only PRs targeting `main` run the IntelliJ and JDBC compatibility matrices. IDE
jobs verify the exact ZIP produced by the PR merge build against each of the
three 2025 SDKs. The shared catalog generates independent database jobs for
MySQL, HSQLDB, PostgreSQL and H2, including Java 8/21 probes. Each uses the same
`--suite ID` adapter as the local aggregate; one engine's failure does not cancel
the others. `--database-compatibility-only` aggregates these entries locally.
Branch pushes and tag releases do not run compatibility matrices. Run
`python scripts/one-shot-test.py` on Linux to reproduce the complete validation
locally; `--compatibility-only` runs both matrices. Ordinary builds use the
checksum-pinned Gradle wrapper and downloaded Maven/IntelliJ dependencies. See
the [script reference](SCRIPTS.md).

These results establish binary/API compatibility for the named builds. They do not establish every patch/future IDE release, interactive UI behavior, project-close behavior or dynamic unload. Hosted results are reported by the workflow checks on each PR; local results do not imply that every hosted suite has run.

## Database and Java 8 matrix

The release matrix covers 27 MySQL/HSQLDB/PostgreSQL combinations at server
floors, driver generations and SQL feature boundaries. Plugin services run on
Java 21; compatible JDBC drivers and HSQLDB servers also run on Java 8. Downloads
and disposable fixtures retain their existing cleanup and isolation checks.

| Server | Selected JDBC versions | Cases |
|---|---|---|
| MySQL 5.5.62 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 5.6.51, 5.7.44 | Connector/J 8.0.33 | 2 |
| MySQL 8.4.2 | Connector/J 8.0.33, 26.7.0 | 2 |
| HSQLDB 2.2.9, 2.3.6, 2.4.1, Maven artifact 2.5.2 | Matching server/driver | 4 |
| HSQLDB 2.6.1, 2.7.4 | Matching `-jdk8` artifact | 2 |
| PostgreSQL 8.4.22, 9.0.23 | pgJDBC 42.2.27, 42.7.3 | 4 |
| PostgreSQL 9.1.24, 9.4.26, 10.23, 12.22, 18.6 | pgJDBC 42.2.27, 42.7.14 | 10 |

These samples retain the server floors and legacy/current driver families.
PostgreSQL additionally covers the 9.1 catalog/driver-policy boundary, JSONB
(9.4), identity/sequences (10), generated columns (12), and the current server.
Intermediate releases and every historical driver/server pairing are not release
claims. HSQLDB 2.5.2 is retained because that Maven artifact reports engine 2.6.0.

PostgreSQL is a native server: Java 8 compatibility applies to its JDBC driver. The plugin packages pgJDBC 42.7.14 (Java 8 bytecode). pgJDBC's documented server floor is 8.4; releases from 42.7.4 no longer guarantee servers older than 9.1, so select 42.7.3 or 42.2.27 for 8.4/9.0. See the [pgJDBC download policy](https://jdbc.postgresql.org/download/).

The PostgreSQL matrix runs on Linux with Docker. Windows retains its native MySQL/HSQLDB matrix and can run PostgreSQL integration tests against an external server. Selected fixtures for 8.4.22, 9.0.23 and 10.23 build checksum-pinned vendor sources because usable official image tags are unavailable. All fixture ports bind to loopback; fixtures are disposable. To run PostgreSQL alone:

```bash
python scripts/postgres-compatibility.py --cleanup
```

Space-constrained Docker VFS hosts can add `--host-compiler` for the historic sources; this requires GCC 14+, make and a Linux glibc no newer than the fixture's 2.41. The default builds sources inside Docker.

Each of the 14 PostgreSQL cases checks selected/downloaded/local-JAR identity and Java 8 connectivity, then runs production CRUD, pagination, metadata, schema/table/column DDL, native CREATE replay with sequence state, all three exports, SQL reimport with binary/temporal/UUID/array values, savepoint rollback, cancellation and recovery from an aborted transaction. JSONB is tested from 9.4, identity from 10 and computed columns from 12, matching the server's capabilities. PostgreSQL schemas are explorer namespaces within the connection's selected physical database.


The floor exercised is MySQL 5.5, HSQLDB 2.2.9 and PostgreSQL 8.4. MySQL text export requires utf8mb4. Historical patches and arbitrary driver/server pairings are not exhaustively tested. Match HSQLDB drivers to server/file formats and choose a compatible Connector/J for MySQL.

## Driver selection and version detection

The wizard selects bundled, downloaded or local-JAR drivers. Downloads are explicit and checksum-verified; version strings are validated before constructing Maven paths. A downloaded release is stored in the JDBC cache and listed in the bundled driver list afterwards, so it is downloaded at most once and can be reused after restarting the IDE. Stored JARs are re-validated before each use, and a selection naming a JAR that is no longer stored is reported rather than silently replaced by the packaged driver. Isolated classloading ensures the selected driver is used. Changing active driver settings retires existing sessions.

Downloaded drivers are never pruned automatically; clearing the IDE JDBC cache removes them and the bundled list returns to the packaged driver.

Test Connection reads DatabaseMetaData after authenticated connection and reports server/driver versions and elapsed time. It cannot reliably infer versions when authentication or protocol negotiation fails, and does not replace the chosen driver automatically.

Legacy HSQLDB schema/SCRIPT handling and MySQL column-rename syntax were exercised by the matrix. HSQLDB multi-statement DDL is not atomic. Full CREATE reconstruction may be unavailable on older engines and is labeled partial. See [CONTRIBUTING](CONTRIBUTING.md) for portable baseline tests.

## H2 integration

H2 2.3.232 is bundled. The H2 matrix tests drivers/servers 2.1.214, 2.2.224 and
2.3.232: memory/file, five TCP pairs (same-version and oldest/newest in both directions), and MySQL/PostgreSQL-mode
service workflows. Drivers 2.1.214 and 2.2.224 also run real Java 8 probes and
servers; 2.3.232 requires Java 11+ bytecode and runs on Java 21, with a negative
Java 8 check. A dedicated bundled-driver TLS fixture verifies certificate trust,
production services, authentication failure redaction and recovery. Native view
DDL replay and typed exports run once per embedded driver and compatibility mode.
TCP pairs retain the production workflow and Java-runtime checks; bundled TCP/TLS
regressions own transport cancellation and recovery.

The complete production UI scenario supports an H2 TCP fixture in a single dark release scenario. Manual reviews can select other themes. See [H2 validation](H2_TESTING.md) for commands and evidence paths.
H2 1.x migration, Java 11 execution itself and full SQL compatibility-mode
emulation are not covered.
