# Releasing Lattice

## Before the first release

Enable Actions and configure signing secrets before tagged releases; see [signing and Marketplace publishing](MARKETPLACE.md). The publish job uses the repository's `GITHUB_TOKEN` with `contents: write`.

Validate the change through a PR targeting `staging` or `main`. That workflow runs the database/Java 8 and IDEA 2025.1–2025.3 compatibility matrices on the PR merge build. Every branch push runs baseline build, live and UI checks; inspect the retained UI evidence. Merge the release commit into `main` before creating its tag. There is no manual release or hosted smoke-upload trigger; use local dry runs below.

## Prepare a version

1. Update `pluginVersion` in `gradle.properties` for ordinary local builds.
2. Move relevant `docs/CHANGELOG.md` entries into a matching version section, including any prerelease suffix, and keep an Unreleased section for future changes.
3. Run `python scripts/test.py --live --mysql --hsqldb --postgres --build` with the isolated fixtures, plus the Python tooling tests and compatibility checks.
4. Review the packaged plugin and commit the changes.
5. From the merged `main` commit, create and push a new annotated tag, for example:

```bash
git tag -a v0.0.2-alpha -m "Lattice 0.0.2-alpha"
git push origin v0.0.2-alpha
```

These are release commands for the maintainer; no tag is created by the preparation scripts. Tags use `vX.Y.Z` or a SemVer prerelease such as `vX.Y.Z-alpha` / `vX.Y.Z-rc.1`, without numeric leading zeroes. Build metadata suffixes are not supported. Release builds derive the plugin/ZIP version from the tag, overriding the default property. Prerelease suffixes set GitHub's prerelease flag and disable latest designation. Do not reuse a published tag.

## Automatic pipeline

The tag workflow:

1. Accepts only newly created `v*` tags. Checks out full Git history and requires the tag commit to be reachable from `origin/main` before any downstream job starts. Tags on unmerged branch commits fail this guard; updates/deletions do not release.
2. Validates the version/tag and patch notes, runs tooling, cross-platform baseline and Gradle pure/live regression suites with fresh MySQL 8.4, HSQLDB and PostgreSQL 18 fixtures.
3. Builds `Lattice-X.Y.Z.zip` against IntelliJ 2025.1 / Java 21; embeds the tag version, patch notes and license notices.
4. Runs the real IntelliJ HSQLDB UI scenario in Dark theme and focused Light-theme review at the release version, signs and verifies the ZIP, validates archive paths, descriptor, bundled drivers/notices and bytecode, then generates SHA-256 asset checksums and the corresponding-source asset.
5. Publishes a GitHub Release only when the main-ancestry guard, build/tests and Windows/macOS baseline checks succeed. Database and IDE compatibility matrices run exclusively on PRs targeting `staging` or `main`, rather than again on tags.
6. After GitHub publication succeeds, submits the same signed ZIP to Marketplace's public Stable channel with `isHidden=false`. Alpha/RC tags remain GitHub prereleases; Marketplace visibility follows JetBrains approval.

Release assets are the plugin ZIP, the unmodified MySQL Connector/J 26.7.0 corresponding-source archive and `SHA256SUMS`. The GitHub description combines the matching changelog section (or populated Unreleased notes) with all commits since the preceding reachable lower-version release tag. Marketplace What's New uses the same patch notes rendered as HTML headings, bullet lists and paragraphs; inline code is supported and raw HTML is escaped. The first release includes the reachable commit history.

Build/test/verification failures publish no release. The publish job uploads assets to a draft, then makes it public after successful upload. A failed GitHub publish can leave a draft; remove that failed draft before retrying. Diagnostics and candidate artifacts are retained for seven days. A GitHub publish retry fails if a release already exists; it does not overwrite published assets. Use a new version for changed published artifacts. A failed Marketplace submission leaves the GitHub Release intact: check whether Marketplace accepted the version, then rerun only the Marketplace job if it is absent. Do not rerun a successful GitHub publish job. The workflow does not perform project-close/dynamic-unload UI tests.

## Local dry run

Python 3.11+ is required (3.12 recommended for SDK archive extraction).

```text
python scripts/release.py --version 1.0.0
python scripts/test.py --live --mysql --hsqldb --postgres --build --version 1.0.0 --notes-file build/release/patch-notes.html
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.0.zip --version 1.0.0 --source build/release/mysql-connector-j-26.7.0-source.tar.gz --checksums build/release/SHA256SUMS
python scripts/verify-plugin.py build/distributions/Lattice-1.0.0.zip --ide-version 2025.1
```

Use `--tag vX.Y.Z` when the local tag exists and points to HEAD. Scripts generate files locally and never publish. The same commands work on Windows, Linux and macOS. Use python3 if required by your Python installation. Set JAVA_HOME to JDK 21; Docker must be running for the owned MySQL fixture. See the [script reference](SCRIPTS.md) for cache and optional SDK overrides.

For dependency upgrades, update Gradle dependencies, bundled fallback JARs, third-party notices/license texts and the pinned corresponding-source URL/checksum together.
