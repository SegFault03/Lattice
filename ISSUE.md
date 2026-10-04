# Reporting an issue

Use the repository's GitHub Issues tab and choose the bug or feature template. Check for an existing report first.

For a bug, include:

- Lattice version and installation source.
- IntelliJ edition/version/build, operating system and IDE runtime version.
- Database product/version, JDBC driver/version and driver source (bundled/download/local JAR).
- Connection mode and a redacted URL; never include passwords, private hosts, tokens or production data.
- Reproduction steps, expected behavior and actual behavior.
- A minimal synthetic schema/query and relevant sanitized error/IDE log excerpt.
- Whether the same operation works in another JDBC client.

For a feature, describe the task you want to complete, the current obstacle and the proposed behavior. UI screenshots may help, after removing private information.

Security concerns should follow `SECURITY.md` rather than a public bug report. Compatibility claims and known limits are documented in `README.md` and the compatibility report.
