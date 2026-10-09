# Changelog

Patch notes are selected by the exact release version, including prerelease suffixes. The workflow appends commit history since the preceding reachable lower-version tag.

## [Unreleased]

- Plain text fields, dropdown selectors and editable selectors share their text inset; the shared border no longer adds IntelliJ's native combo-box padding twice.
- Driver discovery runs off the UI thread, follows child Maven version overrides and validates relative parent coordinates. Switching database types applies the matching project default while preserving manual and saved selections.
- Driver identity stays stable when selected artifacts disappear; session switches retire the previous connection. Retained JAR validation checks entry data/CRCs, removes corrupt owned files, and preserves valid files on cancellation.
- PR checks now validate the merge commit and reuse its built ZIP for compatibility. Superseded checks are cancelled; release source filenames are passed between jobs and release UI checks use the real IDE.

- Downloaded JDBC drivers are kept in the IDE cache and offered in the bundled driver list, so a version is downloaded at most once and reused for later connections instead of being fetched again.
- The packaged driver version is read from the shipped driver JAR instead of duplicated text, and a bundled selection naming a JAR that is no longer stored is reported instead of silently falling back.

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
