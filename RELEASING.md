# Releasing Lattice

## Before the first release

Connect this checkout to the intended GitHub repository and push the release-preparation commit. Ensure the workflow is present on the repository's default branch so its manual Run workflow option is available. Enable Actions and private vulnerability reporting in the repository settings. The workflow uses the repository's `GITHUB_TOKEN`; the publish job requests `contents: write`. No personal access token or Marketplace secret is required for GitHub Releases.

Run **Build, test and release → Run workflow** on the intended branch to validate the hosted pipeline. Manual runs never publish. Review its test and compatibility reports. Complete the separately planned IntelliJ UI checks before calling the release ready for users.

## Prepare a version

1. Update `pluginVersion` in `gradle.properties` for ordinary local builds.
2. Move relevant `CHANGELOG.md` entries into a `## [X.Y.Z]` section and keep an Unreleased section for future changes.
3. Run `./gradlew test integrationTest buildPlugin` with the isolated fixtures, plus the Python tooling tests and compatibility checks.
4. Review the packaged plugin and commit the changes.
5. Create and push a new annotated stable tag, for example:

```bash
git tag -a v1.0.0 -m "Lattice 1.0.0"
git push origin v1.0.0
```

These are release commands for the maintainer; no tag is created by the preparation scripts. Tags must have the form `vX.Y.Z` without leading zeroes. Release builds derive the plugin/ZIP version from the tag, overriding the default property. Do not reuse a published tag.

## Automatic pipeline

The tag workflow:

1. Checks out full Git history and validates the version/tag and patch notes.
2. Runs the Python release-tool tests and Gradle pure/live regression suites with fresh MySQL 8.4 and in-memory HSQLDB fixtures.
3. Builds `Lattice-X.Y.Z.zip` against IntelliJ 2025.1 / Java 21; embeds the tag version, patch notes and license notices.
4. Validates archive paths, descriptor, bundled drivers/notices and bytecode, then generates SHA-256 asset checksums.
5. Verifies that exact ZIP against IDEA 2025.1, 2025.2 and 2025.3 in separate jobs with checksum-verified official SDK downloads. Every verdict must be Compatible.
6. Publishes a GitHub Release only when all build/test/verification jobs succeed.

Release assets are the plugin ZIP, the unmodified MySQL Connector/J 9.0.0 corresponding-source archive and `SHA256SUMS`. The description combines the matching changelog section (or populated Unreleased notes) with all commits since the preceding reachable lower-version release tag. The first release includes the reachable commit history.

Build/test/verification failures publish no release. The publish job uploads assets to a draft, then makes it public after successful upload. A failed publish can leave a draft; remove that failed draft before retrying. Diagnostics and candidate artifacts are retained for seven days. A retry fails if a release already exists; it does not overwrite published assets. Use a new version for changed published artifacts. The workflow does not publish to JetBrains Marketplace, sign the plugin, or run interactive UI tests.

## Local dry run

Python 3.11+ is required (3.12 recommended for SDK archive extraction).

```powershell
$env:LATTICE_TEST_BINARIES = '/path/to/workspace/intellij-extension-test-binaries'
$env:JAVA_HOME = "$env:LATTICE_TEST_BINARIES/ides/2025.1/jbr"
python scripts/release.py --version 1.0.0
.\gradlew.bat "-Plattice.ide.home=$env:LATTICE_TEST_BINARIES/ides/2025.1" `
    -PreleaseVersion=1.0.0 -PreleaseNotesFile=build/release/patch-notes.html test integrationTest buildPlugin
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.0.zip --version 1.0.0 `
    --source build/release/mysql-connector-j-9.0.0-source.tar.gz --checksums build/release/SHA256SUMS
python scripts/verify-plugin.py build/distributions/Lattice-1.0.0.zip --ide-home "$env:LATTICE_TEST_BINARIES/ides/2025.1"
```

Use `--tag vX.Y.Z` when the local tag exists and points to HEAD. Scripts generate files locally and never publish. The Windows/Bash standalone packagers support a version override; distribute their ZIPs with the same license notices and corresponding-source asset.

For dependency upgrades, update both packaging paths, third-party notices/license texts and the pinned corresponding-source URL/checksum together.
