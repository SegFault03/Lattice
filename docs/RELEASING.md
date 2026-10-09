# Releasing Lattice

## Before the first release

Connect this checkout to the intended GitHub repository and push the release-preparation commit. Ensure the workflow is present on the repository's default branch so its manual Run workflow option is available. Enable Actions and private vulnerability reporting in the repository settings. The workflow uses the repository's `GITHUB_TOKEN`; the publish job requests `contents: write`. No personal access token or Marketplace secret is required for GitHub Releases.

Run **Build, test and release → Run workflow** on the intended branch to validate the hosted pipeline. Manual runs never publish. Review its test and compatibility reports. The Linux build job also exercises the production UI inside IntelliJ IDEA in Dark theme. Inspect its retained screenshots before releasing.

## Prepare a version

1. Update `pluginVersion` in `gradle.properties` for ordinary local builds.
2. Move relevant `docs/CHANGELOG.md` entries into a matching version section, including any prerelease suffix, and keep an Unreleased section for future changes.
3. Run `python scripts/test.py --live --mysql --hsqldb --build` with the isolated fixtures, plus the Python tooling tests and compatibility checks.
4. Review the packaged plugin and commit the changes.
5. Create and push a new annotated tag, for example:

```bash
git tag -a v0.0.1-alpha -m "Lattice 0.0.1-alpha"
git push origin v0.0.1-alpha
```

These are release commands for the maintainer; no tag is created by the preparation scripts. Tags use `vX.Y.Z` or a SemVer prerelease such as `vX.Y.Z-alpha` / `vX.Y.Z-rc.1`, without numeric leading zeroes. Build metadata suffixes are not supported. Release builds derive the plugin/ZIP version from the tag, overriding the default property. Prerelease suffixes set GitHub's prerelease flag and disable latest designation. Do not reuse a published tag.

## Automatic pipeline

The tag workflow:

1. Checks out full Git history and validates the version/tag and patch notes.
2. Runs the Python release-tool tests and Gradle pure/live regression suites with fresh MySQL 8.4 and in-memory HSQLDB fixtures.
3. Builds `Lattice-X.Y.Z.zip` against IntelliJ 2025.1 / Java 21; embeds the tag version, patch notes and license notices.
4. Runs the real IntelliJ UI scenario in Dark theme at the release version, validates archive paths, descriptor, bundled drivers/notices and bytecode, then generates SHA-256 asset checksums. The source-preparation step supplies the actual archive filename to validation, artifact upload and publication.
5. Verifies that exact ZIP against IDEA 2025.1, 2025.2 and 2025.3 in separate jobs with checksum-verified official SDK downloads. Every verdict must be Compatible.
6. Runs the JDBC/Java 8 matrix and Windows/macOS baseline checks; publishes a GitHub Release only when every build/test/verification job succeeds.

Release assets are the plugin ZIP, the unmodified MySQL Connector/J 26.7.0 corresponding-source archive and `SHA256SUMS`. The description combines the matching changelog section (or populated Unreleased notes) with all commits since the preceding reachable lower-version release tag. The first release includes the reachable commit history.

Build/test/verification failures publish no release. The publish job uploads assets to a draft, then makes it public after successful upload. A failed publish can leave a draft; remove that failed draft before retrying. Diagnostics and candidate artifacts are retained for seven days. A retry fails if a release already exists; it does not overwrite published assets. Use a new version for changed published artifacts. The workflow does not publish to JetBrains Marketplace, sign the plugin, or perform project-close/dynamic-unload UI tests.

## Local dry run

Python 3.11+ is required (3.12 recommended for SDK archive extraction).

```text
python scripts/release.py --version 1.0.0
python scripts/test.py --live --mysql --hsqldb --build --version 1.0.0 --notes-file build/release/patch-notes.html
python scripts/prepare-source-asset.py
python scripts/check-release-archive.py build/distributions/Lattice-1.0.0.zip --version 1.0.0 --source build/release/mysql-connector-j-26.7.0-source.tar.gz --checksums build/release/SHA256SUMS
python scripts/verify-plugin.py build/distributions/Lattice-1.0.0.zip --ide-version 2025.1
```

Use `--tag vX.Y.Z` when the local tag exists and points to HEAD. Scripts generate files locally and never publish. The same commands work on Windows, Linux and macOS. Use python3 if required by your Python installation. Set JAVA_HOME to JDK 21; Docker must be running for the owned MySQL fixture. See the [script reference](SCRIPTS.md) for cache and optional SDK overrides.

For dependency upgrades, update Gradle dependencies, bundled fallback JARs, third-party notices/license texts and the pinned corresponding-source URL/checksum together.
