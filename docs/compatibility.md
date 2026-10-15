# IntelliJ and database compatibility

## IntelliJ baseline

Production targets Java 21 and IntelliJ IDEA 2025.1 (build 251). Plugin Verifier 1.410 checked the packaged ZIP against full SDKs without ignored compatibility errors:

The build-tool baseline is Gradle 9.8.0 through the checksum-pinned wrapper and IntelliJ Platform Gradle Plugin 2.19.0. Pure tests, plugin packaging, UI previews, live MySQL/HSQLDB/PostgreSQL flows, the Java 8 JDBC probes and the IntelliJ matrix are validated separately so a dependency change can be checked against each layer.

| IDEA | Build | Result |
|---|---|---|
| 2025.1 Community | 251.23774.435 | Compatible |
| 2025.2 Community | 252.23892.409 | Compatible |
| 2025.3 Unified/Ultimate | 253.28294.334 | Compatible |
| 2026.2.3 Ultimate | 262.10968.63 | Compatible in earlier compatibility validation |

Only PRs targeting `staging` or `main` run the IntelliJ and JDBC compatibility matrices. IDE jobs verify the exact ZIP produced by the PR merge build against each of the three 2025 SDKs; the database job uses `python scripts/one-shot-test.py --database-compatibility-only` for MySQL, HSQLDB and PostgreSQL, including Java 8 probes. Branch pushes and tag releases do not run compatibility matrices. Run `python scripts/one-shot-test.py` on Linux to reproduce the complete validation locally; `--compatibility-only` runs both matrices. Ordinary builds use the checksum-pinned Gradle wrapper and downloaded Maven/IntelliJ dependencies. See the [script reference](SCRIPTS.md).

These results establish binary/API compatibility for the named builds. They do not establish every patch/future IDE release, interactive UI behavior, project-close behavior or dynamic unload. The Windows build/test path has been executed locally; hosted Linux/macOS builds are configured but await their first GitHub run.

## Database and Java 8 matrix

The Linux one-shot runner exercises all 57 combinations below with service-level database flows and matching JDBC connection/query probes on Java 8. Plugin services run on Java 21; HSQLDB servers run on Java 8. The plugin itself requires Java 21. Drivers and database server fixtures are processed one at a time and deleted after each case when downloaded by the run.

| Server | Selected JDBC versions | Combinations |
|---|---|---|
| MySQL 5.5.62 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 5.6.51 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 5.7.44 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 8.4.2 | Connector/J 8.0.33, 8.4.0, 9.0.0, 26.7.0 | 4 |
| HSQLDB 2.2.9, 2.3.0, 2.3.6, 2.4.1, 2.5.0 | Matching server/driver | 5 |
| HSQLDB Maven artifact 2.5.2 | Matching artifact; engine reports 2.6.0 | 1 |
| HSQLDB 2.6.1, 2.7.0, 2.7.3, 2.7.4 | Matching `-jdk8` artifact | 4 |
| PostgreSQL 8.4.22, 9.0.23 | pgJDBC 42.2.27, 42.7.3 | 4 |
| PostgreSQL 9.1.24, 9.2.24, 9.3.25, 9.4.26, 9.5.25, 9.6.24 | pgJDBC 42.2.27, 42.7.14 | 12 |
| PostgreSQL 10.23, 11.22, 12.22, 13.23, 14.24, 15.19, 16.15, 17.11, 18.6 | pgJDBC 42.2.27, 42.7.14 | 18 |

PostgreSQL is a native server: Java 8 compatibility applies to its JDBC driver. The plugin packages pgJDBC 42.7.14 (Java 8 bytecode). pgJDBC's documented server floor is 8.4; releases from 42.7.4 no longer guarantee servers older than 9.1, so select 42.7.3 or 42.2.27 for 8.4/9.0. See the [pgJDBC download policy](https://jdbc.postgresql.org/download/).

The PostgreSQL matrix runs on Linux with Docker. Windows retains its native MySQL/HSQLDB matrix and can run PostgreSQL integration tests against an external server. Fixtures for 8.4.22, 9.0.23, 9.2.24, 10.23 and 11.22 build checksum-pinned vendor sources because usable official image tags are unavailable. All fixture ports bind to loopback; fixtures are disposable. To run PostgreSQL alone:

```bash
python scripts/postgres-compatibility.py --cleanup
```

Space-constrained Docker VFS hosts can add `--host-compiler` for the historic sources; this requires GCC 14+, make and a Linux glibc no newer than the fixture's 2.41. The default builds sources inside Docker.

Each of the 34 PostgreSQL cases checks selected/downloaded/local-JAR identity and Java 8 connectivity, then runs production CRUD, pagination, metadata, schema/table/column DDL, native CREATE replay with sequence state, all three exports, SQL reimport with binary/temporal/UUID/array values, savepoint rollback, cancellation and recovery from an aborted transaction. JSONB is tested from 9.4, identity from 10 and computed columns from 12, matching the server's capabilities. PostgreSQL schemas are explorer namespaces within the connection's selected physical database.


Checks cover selected JAR/classloader identity, download/local-JAR modes, metadata, schema/table creation, pagination/count, insert/update/delete, failed-batch rollback, column changes, native CREATE export and SQL export/reimport row/timestamp preservation. The baseline suite also passed 11 JUnit methods, 300 functional assertions and fallback-driver lifecycle checks.

Validation on 2026-10-13 passed all 34 PostgreSQL cases and all 23 existing MySQL/HSQLDB cases. The PostgreSQL bundled-driver suite also passed against an authenticated 18.6 server. After normalizing serial aliases and unbounded TEXT metadata, the 8.4.22 and 10.23 cases were rerun with both selected drivers, and the current-server regression suite was repeated. The real IntelliJ 2025.1 PostgreSQL scenario passed schema/table/column DDL, grid validation and rollback, all three persisted exports, native CREATE display, SQL outcomes, cancellation and recovery. Plugin Verifier passed the exact packaged candidate on all three 2025 IDE targets.

The floor exercised is MySQL 5.5, HSQLDB 2.2.9 and PostgreSQL 8.4. MySQL text export requires utf8mb4. Historical patches and arbitrary driver/server pairings are not exhaustively tested. Match HSQLDB drivers to server/file formats and choose a compatible Connector/J for MySQL.

## Driver selection and version detection

The wizard selects bundled, downloaded or local-JAR drivers. Downloads are explicit and checksum-verified; version strings are validated before constructing Maven paths. A downloaded release is stored in the JDBC cache and listed in the bundled driver list afterwards, so it is downloaded at most once and can be reused after restarting the IDE. Stored JARs are re-validated before each use, and a selection naming a JAR that is no longer stored is reported rather than silently replaced by the packaged driver. Isolated classloading ensures the selected driver is used. Changing active driver settings retires existing sessions.

Downloaded drivers are never pruned automatically; clearing the IDE JDBC cache removes them and the bundled list returns to the packaged driver.

Test Connection reads DatabaseMetaData after authenticated connection and reports server/driver versions and elapsed time. It cannot reliably infer versions when authentication or protocol negotiation fails, and does not replace the chosen driver automatically.

Legacy HSQLDB schema/SCRIPT handling and MySQL column-rename syntax were exercised by the matrix. HSQLDB multi-statement DDL is not atomic. Full CREATE reconstruction may be unavailable on older engines and is labeled partial. See [CONTRIBUTING](CONTRIBUTING.md) for portable baseline tests.

## H2 integration

H2 2.3.232 is bundled. Ordinary unit checks exercise embedded memory and file
connections, production service workflows, generated-column metadata, native
DDL replay and isolated driver disposal. TCP URLs are available, but live TCP/SSL,
compatibility modes, H2 1.x migration, the Java 8 matrix and real-IDE UI review
are not yet validated. See [provider scope](DATABASE_PROVIDERS.md#h2-validation-scope).
