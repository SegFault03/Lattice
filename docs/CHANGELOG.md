# Changelog

Patch notes are selected by the exact release version, including prerelease suffixes. The workflow appends commit history since the preceding reachable lower-version tag.

## [Unreleased]

### SQL editor

- Highlight `.sql` files for the database dialect of the connection you choose (MySQL, PostgreSQL, HSQLDB, H2, or generic SQL), with its own colors, a connection banner, per-file and project defaults, and Settings | Tools | Lattice SQL Editor. This replaces the IDE's highlighting-only SQL file type for `.sql`; a notice offers to turn it off, and it starts off where the Ultimate Database plugin is enabled.
- Complete keywords, functions, types, tables, views, aliases and columns in `.sql` files for the dialect of the file's connection, with foreign key joins after `JOIN` and join conditions after `ON`, explorer icons with `PK`/`FK` flags, columns of the statement's tables, quoting that follows the dialect's case folding, and an option in Settings | Tools | Lattice SQL Editor.
- Validate SQL locally, with no database calls: syntax errors, unknown tables and columns (with "did you mean" fixes), ambiguous columns and alias mistakes, as five inspections under Editor | Inspections | Lattice SQL. The schema checks use only the cached schema, stay silent when it may be out of date, and skip queries they cannot fully see.
- Silence a finding with `-- noinspection <ShortName>` above a statement or at the top of a file, or with the Suppress for statement and Suppress for file fixes. `.sql` files get their own file icon, and data types and functions use colors of their own in the color settings.
- Finish the SQL editor settings: a keyword case option for completion, a Project Defaults page (default connection, reading the project's configuration files, offers you turned down), and Tools | Lattice: Restore the IDE's Built-in SQL Handling.
- Keep the SQL editor responsive on large inputs: completion and hover analyze the statement around the caret in files over 100 KB, work is cancelled as you type, and SQL in strings and hover keep working while the IDE indexes.
- Find the databases a project's configuration files point at (`application*.properties` and `.yml`, `persistence.xml`, `hibernate.cfg.xml`, Tomcat `context.xml`): a saved connection that matches applies to the project's SQL by itself, an unsaved one is offered with the connection dialog pre-filled, and the database type sets the dialect when nothing else does. Passwords and URL parameters are never read; a user name next to the URL pre-fills the new connection.
- Treat SQL strings in Java and Kotlin code as SQL: highlighting, completion and hover follow the file's connection. Strings are recognized by `@Language("SQL")` or `// language=SQL`, by the call they are passed to (JDBC, Spring `JdbcTemplate`, JPA, Hibernate, jOOQ, Exposed and native `@Query`; the list is editable), or by a strict check of the text. Concatenations, text blocks, raw strings and templates work, with a placeholder for what is only known at run time, and Alt+Enter marks a string as SQL or not.
- Show tables, columns, aliases and schemas on hover in `.sql` files: comments, types, keys, foreign key targets as links, what references a table, and an Open in Lattice link. Table cards list columns as an aligned table, alias cards show their table's card, and the popup's location bar names the connection and database type. The card reads the cached schema of the file's connection.
- Use the only saved connection for SQL files without a choice, show the file's dialect and connection in the status bar, and forget bindings to deleted connections.
- Read foreign key targets and table and column comments with the schema metadata, and keep them in the offline metadata cache.
- Make the Maven plugin an optional dependency so Lattice loads in IDEs without it.

### Data sources and explorer

- List PostgreSQL partitioned tables, materialized views and foreign tables in the explorer and the SQL editor. Materialized views are read-only like views; foreign tables are dropped with `DROP FOREIGN TABLE` and have no Alter or Truncate.
- Connect MySQL with TLS when the server offers it, and stop MySQL connects to unreachable hosts after 10 seconds. A slow connect no longer blocks the explorer or the IDE.
- Check the connection form on Save as well as on Test, and mask passwords in the JDBC URL shown and copied by the connection dialog. Driver origins read Bundled, Downloaded or Local Maven.
- Report MySQL generated columns as generated rather than auto-increment, so SQL INSERT exports leave them out.
- Keep a connection's position in the explorer after editing it, and look up table columns without listing the whole schema first.

### Table and SQL tools

- Keep NULL when an untouched NULL cell is committed, and show and edit binary values as hex instead of `[B@…`.
- Load tables with oversized BLOB or CLOB values: the value shows a truncated, read-only preview with its full size, and page or selection exports refuse it rather than writing it incomplete.
- Stop auto-refresh after a failed load and report it in the status line instead of opening an error dialog on every tick.
- Add SQL to an open console below the existing text instead of replacing it; plain queries in the console no longer reload open table editors and the explorer.
- Keep the selection and column widths when a table refreshes, reuse an editor's read connection between pages, and keep the page-size control in sync when a reload is refused.
- Offer each engine's common column types in create and alter dialogs, accept typed types for every engine, quote text defaults such as `42`, and change only the edited clauses when altering a PostgreSQL column.
- Show the table footer in one row, show key columns by icon alone (auto-increment is listed in the inspector), and use platform selection colors and a dark-theme MySQL logo in the table dialogs.

## [0.1.0-alpha]

### Data sources and explorer

- Add PostgreSQL with schema browsing, table editing, SQL consoles, DDL and exports; add H2 memory, file and TCP connections, with MySQL and PostgreSQL compatibility-mode workflows.
- Group connections by database type, filter visible sources, and search across connections, schemas, tables and columns. Show key metadata in table and SQL result grids.
- Cache the last successful metadata for offline browsing and SQL generation, and refresh the explorer and affected editors after database changes.
- Refresh all saved connections in the background when Lattice first opens in each project session, preserving cached metadata when servers are unavailable.
- Preserve expanded and collapsed branches, selection and scroll position across startup, manual and database-change refreshes, including when explorer search is active.

### Table and SQL tools

- Add the Value Workspace for formatted/raw values, column details, hover-only copy feedback and explicitly staged edits. Unconfirmed drafts can be recovered without joining the pending database batch.
- Keep NULL and database-default choices in the inspector draft until Stage change is clicked; support column defaults on existing rows. Show only Revert after staging and keep Copy inside the value box, visible on hover.
- Redesign create/alter dialogs around column navigation and complete SQL previews. Apply changes in batches and retain uncompleted edits after partial DDL failure.
- Improve cancellation and session recovery, preserve generated/default values during schema changes, and keep table actions available as the selection and editor state change.
- Detect PostgreSQL transaction-end commands with a linear comment scanner, avoiding regex backtracking on crafted SQL input and supporting nested block comments.

### Drivers and interface

- Discover Maven-cached and project-default drivers in the background, preserve manual selections, reuse verified downloads across IDE restarts, and report missing or corrupt artifacts.
- Refresh the connection form and explorer controls for multiple sources, improve theme-aware menus and feedback, and update the welcome screen to show all supported databases and current workflows.
- Add an offline What's New editor tab with illustrated usage guides, a once-per-version update notice, and permanent entry points in the welcome screen and Tools menu.

### Alpha limits

- Multi-step MySQL, HSQLDB and H2 DDL can commit incrementally; review the refreshed schema and remaining edits after a partial failure. PostgreSQL alter batches use a transaction.
- Cached metadata can be stale and contains no table rows. Live operations require a connection; generated MySQL URLs disable TLS, so use a custom URL when TLS is required.
- Project-close and dynamic unload validation remains pending. Pagination without a primary key requires an explicit unique order; connection loss during commit can leave an uncertain server outcome.

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
