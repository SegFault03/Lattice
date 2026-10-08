# Final public-release review

## Scope and corrections

Reviewed the tracked Java services/models, Swing/editor lifecycle paths, database operations and regression coverage, build configuration, dependency/driver packaging, plugin descriptor/icons, documentation, helper scripts and GitHub workflows.

- Public documentation uses repository-relative links and portable commands; it no longer describes a maintainer's workstation or local fixture inventory.
- Optional public tooling is under `scripts/`. The root keeps the standard Gradle wrapper launchers. SDK/server-dependent wrappers and historical private review reports are outside the repository and excluded from Git history. A recovery bundle is retained privately outside Git.
- Gradle resolves platform dependencies automatically. Python helpers use OS user caches, subprocess argument lists and platform-specific wrapper selection. Windows wrapper invocation supports checkout/argument paths containing spaces. Explicit path overrides remain optional.
- Fixture orchestration owns a loopback-only disposable MySQL container and memory-only HSQLDB process, rejects occupied ports and cleans up owned fixtures on failure. CI's MySQL service is paired with this same HSQLDB lifecycle.
- SDK downloads are checksum-verified. Static verifier extraction skips native/JBR links, rejects traversal and reuses only completed extractions. Every verifier run has fresh, separate report and scratch directories.
- The root branding image moved to `assets/icon.png` without changing its bytes. README references it. Production plugin icons and Java/XML resource paths remain under `src/main/resources` and are present in the packaged JAR.
- Removed setup-specific database engine guessing after protocol/authentication failure. The selected JDBC protocol now fails explicitly; authenticated server-version detection remains available. Unsupported-URL errors omit the URL and credentials.
- SQL type, metadata, URL and driver filename casing uses Locale.ROOT; Turkish-locale DDL size handling is regression-tested.
- Corrected untested server-version claims and the descriptor's unsupported import claim. Documented generated MySQL URLs' TLS behavior rather than implying secure defaults.

## Validation

- Windows/JDK 21: Gradle pure tests, integration tests, fallback-driver lifecycle and version-override ZIP packaging passed through the public Python runner.
- Linux/JDK 21: Gradle 9.8.0 with IntelliJ Platform Gradle Plugin 2.19.0 passed clean-cache unit tests, ZIP packaging, the five UI previews, and live MySQL 8.4/HSQLDB 2.7.4 functional tests.
- 11 JUnit methods and 300 live functional assertions passed. The 55 Python tooling tests passed with one Windows-only skip; coverage includes the new proxy-truststore setup path.
- All 23 JDBC/server combinations and Java 8 driver probes passed, including Connector/J 26.7.0 against MySQL 8.4.2 and HSQLDB 2.7.4.
- Workflow actionlint passed for both release and Windows/Linux/macOS CI definitions.
- Final ZIP validation checked portable paths, version/baseline, Java 21 bytecode, production dependencies and license notices. Plugin Verifier 1.410 returned Compatible on IntelliJ 2025.1, 2025.2 and 2025.3.
- Checked public Markdown links, descriptor-declared implementation/resource paths, unchanged branding image content and absence of native test executables/libraries or private setup paths in the publication tree.

## Remaining release gates and limits

Hosted Windows/Linux/macOS CI and the tag release pipeline still need their GitHub runs. Local Linux validation used the disposable Docker fixtures and cleaned the images it downloaded. Interactive IntelliJ UI/keymap/scale/theme behavior, project close and dynamic unload remain deferred.

Plugin Verifier cannot establish those interactive behaviors. It still reports two deprecated IntelliJ API usages and one internal hover-painting API use; these are existing product-code API notices, separate from the upgraded build dependencies.

Generated MySQL URLs disable TLS; remote/TLS-required connections should use explicit custom URL security settings. HSQLDB multi-statement DDL is not atomic, tables without primary keys require an explicit unique order for stable paging, and connection loss during commit can leave an uncertain server outcome. Known limits are also recorded in README.

No remote, tag, push or release publication was performed during this review.
