# IntelliJ and database compatibility

## IntelliJ baseline

Production targets Java 21 and IntelliJ IDEA 2025.1 (build 251). Plugin Verifier 1.410 checked the packaged ZIP against full SDKs without ignored compatibility errors:

| IDEA | Build | Result |
|---|---|---|
| 2025.1 Community | 251.23774.435 | Compatible |
| 2025.2 Community | 252.23892.409 | Compatible |
| 2025.3 Unified/Ultimate | 253.28294.334 | Compatible |
| 2026.2.3 Ultimate | 262.10968.63 | Compatible in earlier compatibility validation |

The release workflow verifies its exact candidate against all three 2025 SDKs. Ordinary builds use the committed Gradle wrapper, with a pinned distribution checksum, and downloaded Maven/IntelliJ dependencies. See [script reference](SCRIPTS.md) for optional verification helpers.

These results establish binary/API compatibility for the named builds. They do not establish every patch/future IDE release, interactive UI behavior, project-close behavior or dynamic unload. The Windows build/test path has been executed locally; hosted Linux/macOS builds are configured but await their first GitHub run.

## Database and Java 8 matrix

All 22 combinations below passed service-level database flows and matching JDBC connection/query probes on Java 8. Plugin services ran on Java 21; HSQLDB servers ran on Java 8. The plugin itself requires Java 21.

| Server | Selected JDBC versions | Combinations |
|---|---|---|
| MySQL 5.5.62 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 5.6.51 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 5.7.44 | Connector/J 5.1.49, 6.0.6, 8.0.33 | 3 |
| MySQL 8.4.2 | Connector/J 8.0.33, 8.4.0, 9.0.0 | 3 |
| HSQLDB 2.2.9, 2.3.0, 2.3.6, 2.4.1, 2.5.0 | Matching server/driver | 5 |
| HSQLDB Maven artifact 2.5.2 | Matching artifact; engine reports 2.6.0 | 1 |
| HSQLDB 2.6.1, 2.7.0, 2.7.3, 2.7.4 | Matching `-jdk8` artifact | 4 |

Checks cover selected JAR/classloader identity, download/local-JAR modes, metadata, schema/table creation, pagination/count, insert/update/delete, failed-batch rollback, column changes, native CREATE export and SQL export/reimport row/timestamp preservation. The baseline suite also passed 11 JUnit methods, 300 functional assertions and fallback-driver lifecycle checks.

The floor exercised is MySQL 5.5 and HSQLDB 2.2.9. MySQL text export requires utf8mb4. Historical patches and arbitrary driver/server pairings are not exhaustively tested. Match HSQLDB drivers to server/file formats and choose a compatible Connector/J for MySQL.

## Driver selection and version detection

The wizard selects bundled, downloaded or local-JAR drivers. Downloads are explicit and checksum-verified; version strings are validated before constructing Maven paths. Isolated classloading ensures the selected driver is used. Changing active driver settings retires existing sessions.

Test Connection reads DatabaseMetaData after authenticated connection and reports server/driver versions and elapsed time. It cannot reliably infer versions when authentication or protocol negotiation fails, and does not replace the chosen driver automatically.

Legacy HSQLDB schema/SCRIPT handling and MySQL column-rename syntax were exercised by the matrix. HSQLDB multi-statement DDL is not atomic. Full CREATE reconstruction may be unavailable on older engines and is labeled partial. See [CONTRIBUTING](CONTRIBUTING.md) for portable baseline tests.
