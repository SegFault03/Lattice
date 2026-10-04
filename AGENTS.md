# Working on Lattice

## Project and platform baseline

Lattice is an IntelliJ IDEA database plugin for MySQL and HSQLDB. Production source is under `src/main/java/com/vibe/ideadb/`; resources and the plugin descriptor are under `src/main/resources/`. Compile against IntelliJ 2025.1/build 251 and Java 21. Java 8 compatibility applies to legacy JDBC/database probes, not the plugin runtime.

Use the committed Gradle wrapper. Public tooling must work on Windows, Linux and macOS without maintainer-specific SDK/server directories. Do not add workstation paths, credentials, IDE caches, native test binaries or generated output to Git. Optional SDK overrides are fine; automatic Gradle dependency resolution is the default. Do not delegate to subagents unless the user asks.

Use `boney0310@gmail.com` as the author and committer email for this repository's commits; do not use an unrelated GitHub CLI account identity. Authentication accounts and commit attribution are separate.

## Scripts and validation

Optional helpers are under `scripts/`; only standard `gradlew` / `gradlew.bat` launchers belong in the root. Read [scripts/README.md](scripts/README.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

- `python scripts/test.py --build`: pure Java tests and ZIP packaging.
- `python scripts/test.py --live --mysql --hsqldb --build`: live/fallback-driver tests with owned disposable fixtures; requires JDK 21 and a running Docker engine.
- `python -m unittest discover -s scripts/tests -v`: development/release tooling tests.
- `scripts/release.py`: version/tag validation, changelog patch notes and commit history.
- `scripts/check-release-archive.py`: ZIP paths, descriptor, bytecode, dependencies/licenses and checksums.
- `scripts/prepare-source-asset.py`: pinned, verified corresponding MySQL source archive.
- `scripts/verify-plugin.py`: exact-ZIP Plugin Verifier checks; owned scratch must remain separate from reports.
- `scripts/common.py`: platform paths, user-cache and Java/Gradle discovery.

Use `python3` if required. Run relevant checks after changes and preserve clear evidence of what was actually executed. Do not claim Linux/macOS, Docker or interactive IDE validation from a Windows-only run. Live suites must use isolated disposable databases; never production data. Tests create/drop schemas. The fixture runner binds published ports to loopback, rejects occupied ports and stops only resources it owns.

## Release workflow

[.github/workflows/ci.yml](.github/workflows/ci.yml) builds/tests Windows, Linux and macOS. [.github/workflows/release.yml](.github/workflows/release.yml) runs on pushed `v*` tags, validates the exact version, executes live tests, builds the ZIP, checks licenses/bytecode, verifies IntelliJ 2025.1/2025.2/2025.3, and publishes only after all release jobs pass. Manual workflow runs are dry runs and never publish.

Read [RELEASING.md](RELEASING.md) before tagging. Stable tags are `vX.Y.Z`; prerelease tags are `vX.Y.Z-alpha`, `vX.Y.Z-beta.1`, etc. Prerelease identifiers follow SemVer precedence; numeric identifiers cannot have leading zeroes. Build metadata suffixes are not supported. The tag controls the packaged version and prerelease status. Update `gradle.properties`, the descriptor default and the matching CHANGELOG section before a planned release.

Release descriptions include patch notes and all commits since the preceding reachable lower-version tag. Assets are the tested plugin ZIP, the pinned corresponding MySQL source archive and SHA256SUMS. Publishing uploads a draft first; prereleases are marked explicitly and are not designated latest. Do not overwrite published tags/assets or move a release tag to a different commit. Failed publication may leave a draft; inspect it before retrying.

Pushing, tagging, publishing and messaging external services require user authorization. Once authorized, complete validation and monitor the workflow to its actual result; report failures honestly and fix authorized issues. Do not bypass failed test/verification gates to publish. Never rewrite already-published history without explicit authorization.

## Packaging, icons and maintenance

`assets/icon.png` is documentation branding. IntelliJ icons live under `src/main/resources/META-INF/` and `src/main/resources/icons/`; check descriptor and Java references before moving them. Gradle packages production JDBC dependencies; `lib/` contains fallback-test JARs. Keep driver versions, notices/license texts and pinned corresponding-source metadata aligned when upgrading dependencies.

Follow existing transaction, original-row identity, PasswordSafe, cancellation, EDT and disposable-service contracts. Keep JDBC work off the EDT. Database/driver failures must not silently change the selected engine. Use Locale.ROOT for protocol/SQL casing. Preserve known limits and defer no requested work silently; see [docs/final-review.md](docs/final-review.md) and [docs/compatibility.md](docs/compatibility.md).
