# Changelog

User-facing changes are recorded here. The tag workflow selects the matching version section, falling back to populated Unreleased notes for a new patch release.

## [Unreleased]

- Portable Gradle/Python build and test commands, OS user caches and owned disposable fixtures.
- Cross-platform build/test CI for Windows, Linux and macOS; public docs contain no maintainer-specific setup.
- Database connection failures no longer silently switch engines based on port numbers; unsupported-URL errors omit credentials.
- SQL type handling and driver lookups use locale-independent casing.
- Tag-triggered builds, functional tests, IntelliJ compatibility checks and GitHub release assets.
- Release notes include patch notes and the commit history since the preceding release tag.
- Public issue templates, contribution/release documentation and bundled third-party notices.

## [1.0.0]

- Database explorer, SQL console, table editor, schema/table design and CSV/JSON/SQL export for MySQL and HSQLDB.
- IntelliJ IDEA 2025.1+ / Java 21 baseline, verified against 2025.1, 2025.2, 2025.3 and 2026.2.3.
- Per-connection JDBC release downloads, manual JAR selection and detected server/driver versions.
- Legacy database compatibility tested with MySQL 5.5–8.4 and HSQLDB 2.2.9–2.7.4, including Java 8 driver probes.
- Atomic edit batches, correct original row identities, configuration/session isolation and stale-result protection.
- PasswordSafe credential storage, draft recovery, bounded asynchronous work and JDBC lifecycle cleanup.
- Stable primary-key pagination, streaming export, typed conversion and exact SQL export/reimport checks.
