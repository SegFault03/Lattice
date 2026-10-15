# Database providers

Lattice is a JDBC database client for arbitrary user schemas. Production code uses
IntelliJ services for application lifecycle and provider interfaces for engine
behavior. It does not require a Spring application context, mapped entities, or
repository interfaces.

## Boundaries

| Component | Responsibility |
|---|---|
| `DatabaseProviders` | Register built-ins and resolve a provider by persisted type or JDBC URL; reject unknown types |
| `DatabaseProvider` | Connection defaults, URL construction, initialization, namespace selection, fetch behavior, transaction suitability and value handling |
| `DriverDescriptor` | Maven coordinates, accepted classes, version/classifier and ordering rules, packaged JAR identification and isolated package namespaces |
| `SqlDialect` | Identifier quoting, qualified names, pagination, generated DDL/DML and native CREATE retrieval |
| `MetadataAdapter` | Catalog/schema enumeration, JDBC metadata and engine-specific enrichment |
| `DatabaseFeature` | Explicitly supported optional operations; providers advertise none by default |
| `DatabaseNamespace` | Independent catalog and schema identifiers |

MySQL, HSQLDB, PostgreSQL and H2 have separate providers, dialects and metadata adapters. Common
SQL construction lives in `AbstractSqlDialect`; common JDBC metadata reading
lives in `JdbcMetadataAdapter`. Engine-specific DDL, compatibility workarounds,
metadata enrichment, streaming rules and SQL literal encoding stay in their
implementations. Shared services remain facades to preserve existing callers.

`ConnectionConfig` and `DatabaseType` retain their persisted field/enum identities.
This refactor requires no settings migration and keeps secrets in PasswordSafe.
A custom JDBC URL must match the selected provider. The connection manager rejects
a mismatch before loading a driver; provider type is part of cached connection
identity so a type change retires existing sessions; the connection dialog infers the selection
from recognized URL prefixes.

The built-in connection form choices are a generic server form and HSQLDB/H2's
server/file/memory form. A new server provider can use the generic form and its own
defaults. A provider with different connection inputs needs an additional form.
The legacy field names in the Swing dialog and HSQLDB fields in persisted settings
remain for compatibility.

## Adding another JDBC database

1. Add a stable `DatabaseType` ID and register its provider in `DatabaseProviders`.
2. Supply a driver descriptor with coordinates, supported version rules, driver
   classes and packages that must load from the selected JAR.
3. Implement connection behavior, metadata and a dialect. Reuse common helpers
   only where their SQL and metadata conventions match the new engine.
4. Advertise supported `DatabaseFeature` operations. Shared DDL entry points guard
   these features; explorer menus disable unsupported actions. Version-dependent
   behavior and table transaction suitability must still be checked against the
   actual server.
5. Add a driver dependency if the driver is to be bundled, and update packaging,
   license notices, compatibility fixtures and product descriptions. Drivers that
   need multiple external dependency JARs require extending the current single-JAR
   selection/loading mechanism.
6. Run the provider contract and end-to-end workflows against the new engine,
   including legacy driver versions where supported.

The current explorer presents one namespace level. Existing APIs convert its
selection into a catalog for MySQL or schema for HSQLDB/PostgreSQL. `DatabaseNamespace` keeps
both identifiers distinct when qualifying names, but an engine requiring an
interactive catalog-and-schema hierarchy also needs corresponding explorer and
service API changes. PostgreSQL connects to one physical database and presents
its schemas at this namespace level. H2 also connects to one physical database and presents schemas at this level. Its dedicated provider supplies memory/file/TCP URLs, H2 DDL, native SCRIPT table exports and generated-column metadata. The existing persisted `hsqlMode` selector is shared by HSQLDB and H2; it does not select the SQL dialect.

PostgreSQL's provider binds inferred target types for JSON/UUID/arrays, detaches
server-specific and temporal values before connections close, opens cursor exports
inside a read transaction, and retries protocol cancellation safely through the
shared query-execution guard. Native CREATE reconstruction uses version-aware
catalog queries, with identity from 10 and computed columns from 12. Generated
columns remain read-only in the grid and are omitted from INSERT exports. Schema
DDL and column alterations use PostgreSQL's transactional semantics, preserving
outer transactions through savepoints.

## Connection ownership

- `DatabaseSession` pins one connection to a console and serializes operations.
  Do not replace it with a fresh pool checkout per query: transactions, temporary
  tables and session settings can span queries.
- `executeBrowser` serializes an entire explorer/DDL operation on the cached
  browser connection. Acquisition alone does not protect subsequent JDBC calls.
- `openConnection` gives an operation an exclusive connection. The caller owns
  cleanup. Grid mutations use the existing transaction and rollback machinery;
  editor reads use `DatabaseTaskScope` for cancellation and cleanup.
- Driver versions remain isolated by classloader. Do not replace explicit driver
  selection with unrestricted global `DriverManager` lookup.

Spring Data JDBC/JPA target mapped aggregates/entities and do not solve dynamic
metadata, SQL dialect differences, selected driver versions or session ownership.
Spring JDBC can reduce boilerplate but is optional. Consider bounded, lazy HikariCP
pools for short operations only after measuring connection costs; pools would need
an adapter around the selected driver and explicit disposal/configuration retirement.
Console sessions must retain their pinning semantics. No pooling dependency is
introduced here.

## Validation

`DatabaseProviderTest` checks provider completeness, feature opt-in, URL validation,
namespace quoting, driver rules, values and concurrent browser serialization.
`ProviderEndToEndTest` runs the same production-service workflow against embedded
HSQLDB/H2 and live MySQL/HSQLDB/PostgreSQL servers: create/browse metadata, insert/update/delete,
rollback, pagination, SQL export/reimport, console namespace selection, table
alterations and cleanup.

```text
./gradlew test buildPlugin
python scripts/test.py --live --mysql --hsqldb --integration-only
```

The existing functional regressions, cancellation tests, credential persistence
checks, isolated driver lifecycle checks and real IntelliJ UI review also apply.
See [Contributing](CONTRIBUTING.md) and [UI previews](UI_PREVIEW.md) for fixture and
IDE setup. Cross-database transactions, automatic retries after uncertain commits,
and support for non-JDBC sources are outside the provider contract.

## H2 validation scope

The bundled H2 2.3.232 runs in ordinary `test`: the shared end-to-end service
workflow covers schema/table DDL, browsing, grid mutations and rollback,
pagination, export/reimport, console namespace selection and table alterations.
`H2FeatureTest` covers file persistence, generated columns, identity and timestamp
precision, native DDL replay and isolated driver disposal. URL tests cover TCP,
SSL custom URLs, Windows paths and existing `.mv.db` paths. Driver selection
accepts H2 2.x; older 1.x databases require an external migration.

`h2IntegrationTest` runs owned TCP/TLS production workflows, failed-authentication
and recovery checks. `H2RegressionTest` also covers typed export replay, native
view DDL, identity state, failed batches/alterations, transaction ownership,
cancellation races and timeouts. The compatibility matrix tests H2 2.1.214,
2.2.224 and 2.3.232 across memory/file, all TCP driver/server pairs and MySQL/
PostgreSQL compatibility-mode service workflows. Java 8 runs 2.1/2.2; 2.3 requires
Java 11+ bytecode and is exercised on Java 21 with an expected Java 8 rejection.

The real IntelliJ UI scenario supports an H2 TCP fixture with full dark/light
functional checks. See [H2 validation](H2_TESTING.md) for suite boundaries,
commands and evidence paths. H2 1.x migration, running on Java 11 itself and
arbitrary compatibility-mode SQL remain outside this matrix. H2 DDL implicitly
commits; schema changes are not rollback-safe console operations.
