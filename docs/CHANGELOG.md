# Changelog

Patch notes are selected by the exact release version, including prerelease suffixes. The workflow appends commit history since the preceding reachable lower-version tag.

## [Unreleased]

- Restore the normal table-header pointer after UI/theme refreshes; show the resize cursor only at column boundaries.
- Add PostgreSQL connections with bundled pgJDBC, schema browsing, SQL consoles, grid editing, DDL and exports; validate current and legacy server/driver combinations.
- Inspect cell values and column definitions beside the grid, with formatted/raw views, pinning, copying, explicit staging, and recovery of unconfirmed edits. Restored inspector text remains separate from the pending database batch and survives delayed or failed metadata loading.
- Redesign create/alter table dialogs with a column navigator and complete SQL preview. Apply additions, property changes, renames and drops in a batch, with validation and recovery after partially committed DDL.
- Refresh explorer metadata and affected editors after database mutations, preserving unsaved edits until they are committed or reverted.
- Retain the last successful metadata for offline browsing and cached SQL generation. Connected INSERT generation loads unknown columns before creating its template.
- Preserve H2 ON UPDATE expressions and identity mode, increment and sequence state when altering other column properties. Keep MySQL type attributes only when compatible with the target type.
- Run focused native cell-inspector interaction and draft-recovery checks at 100% and 125% UI scale on main-targeted PRs, with both-theme assertions and separate evidence.
- Integrate H2 with the staging source picker, explorer grouping/search/filter and table-tab badges; cover aliased/composite primary and foreign keys with packaged and selected drivers.
- Add H2 2.x provider and bundled 2.3.232 driver, memory/file/TCP connection forms, schema/table operations, generated-column metadata and native table DDL exports. Add file/TCP/TLS functional and data regression suites, a driver/server/runtime compatibility matrix, and full dark/light real-IDE H2 scenarios. H2 2.3 requires Java 11+; H2 2.1/2.2 also pass real Java 8 probes.

- Redesign the connection dialog with a scrollable data source picker and provider-specific defaults.
- Group explorer connections by database type with SVG labels and a View only picker for selecting one or more types.
- Search connected sources by data source, schema, table and all column names, combine focused filters, and highlight every match with responsive drawer and inline layouts.
- Overlay solid data source badges on the bottom-left of table tab icons.
- Highlight primary, foreign and combined key headers in table editors and SQL results; preserve source-column identity for aliased queries and clear stale badges.
- Improve live loading indicators, driver-download UI synchronization and query-session recovery before SQL submission.
- Make PostgreSQL UI-wrapper tests portable across Windows paths and wait for the real-IDE Revert reload before filtering; exercise the busy reload with a gated database response.

- Run only embedded functional and plugin build/archive checks on commits. Group tooling, regression/integration, UI and Java/JDBC/IDE compatibility under main-targeted PR validation; staging PRs use the commit tier. Parallelize UI scenarios and remove duplicate H2 integration/build work. Keep release signing/publishing gated on new version tags reachable from main.
- Retry cancellation during slower remote statement startup and synchronize H2 TLS cancellation regressions with server execution. Validate Windows launcher inventories on every host.
- Release only from newly created version tags reachable from main; gate all release jobs on ancestry and disable manual release/Marketplace smoke publishing.

## [0.0.2-alpha]

### JDBC drivers and connections

- Download JDBC drivers once and reuse them from the bundled driver list across connections and IDE restarts.
- Discover driver versions without blocking the UI, including versions inherited from Maven parent projects. Switching database types applies the matching project default while preserving saved and manually selected drivers.
- Read bundled driver versions from their shipped JARs. Missing selected artifacts are reported clearly, and retained JARs are checked for corruption before reuse.
- Preserve driver identity when artifacts disappear, retire the previous connection when switching sessions, and keep valid cached files when a download is cancelled.

### Table and SQL tools

- Table and SQL actions now reflect selection, input, pending edits and busy states. Clear table filters with the new `Clear` action.
- Improve table headers, narrow-window layouts, schema dialogs and toolbars, with consistent light/dark styling and clearer loading, validation and cancellation feedback.
- Align text fields and selectors, improve disabled action icons, and keep keyboard focus visible without leaving a focus outline after mouse clicks.

### Release and validation

- Update Gradle and IntelliJ build tooling, JDBC drivers and the protobuf runtime used by the packaged plugin.
- Sign release ZIPs and verify their signatures before publishing the same tested build to GitHub and JetBrains Marketplace.
- Display What's New as formatted headings and bullet lists instead of a monospace text block.
- Expand CI coverage with production Swing assertions, real-IDE UI reviews, PR merge-commit validation and reusable dependency caches. Validate Marketplace uploads without contacting the service in tooling tests.

## [0.0.2-smoke.1]

- Signed prerelease build for validating Marketplace submission, change notes and installation through the hidden smoke channel.
- Includes the current database explorer, SQL console, table editing and schema tools for smoke testing after installation.
- Includes recent UI action-state, layout and JDBC driver-discovery fixes from the development build.

## [0.0.1-alpha]

- First public alpha of the MySQL/HSQLDB database management plugin for IntelliJ IDEA 2025.1+ / Java 21.
- Database explorer, SQL console, editable table grids, schema/table design and CSV/JSON/SQL exports.
- Per-connection JDBC versions: bundled drivers, checksum-verified downloads, manual JAR selection and authenticated server/driver version detection.
- Representative MySQL 5.5–8.4 and HSQLDB 2.2.9–2.7.4 compatibility coverage, including 23 Java 8 driver probes.
- Atomic edit batches, original-row identities, configuration/session isolation, stale-result protection, draft recovery and PasswordSafe credential storage.
- Bounded asynchronous JDBC work, lifecycle cleanup, stable primary-key pagination, streaming exports, typed conversion and SQL export/reimport regression checks.
- Failed connections preserve the selected database engine; URL rejection omits credentials and SQL/type handling uses locale-independent casing.
- Portable Gradle/Python tooling and Windows/Linux/macOS build CI; tag releases include compatibility checks, patch notes, commit history, checksums and corresponding MySQL driver sources.
- Contributor, issue, security and release documentation, bundled license notices and AGENTS.md guidance.

### Alpha limits

- Production UI flows are exercised in real IntelliJ IDEA. Project-close and dynamic unload validation remains pending.
- HSQLDB multi-statement DDL is not atomic. Stable pagination without a primary key requires an explicit unique order.
- Connection loss during commit can leave an uncertain server outcome. Generated MySQL URLs disable TLS; use an explicit custom URL for remote/TLS-required connections.
