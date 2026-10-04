# Lattice

<img src="src/main/resources/icons/lattice_large.svg" alt="Lattice logo" width="80" height="80">

Lattice is a database management plugin for IntelliJ IDEA. Connect to MySQL or HSQLDB, browse schemas, run SQL, edit table data, design tables and export results from your IDE.

Built with help from **Gemini 3.8**, **GPT-6 Luna** and **GPT-6.1 Sol**.

## Features

- Database explorer for schemas, tables, views, columns and primary keys.
- SQL console with results, history, row limits, timeouts and cancellation.
- Editable table grids with pagination, filtering, batch saves, explicit NULL/default values and draft recovery.
- Create/alter table dialogs and database/schema maintenance.
- CSV, JSON, SQL INSERT and native CREATE TABLE exports.
- Per-connection JDBC driver selection: bundled release, download from Maven Central, or a local JAR.
- Server and JDBC driver version information through **Test Connection**.
- Passwords and custom JDBC URLs stored through IntelliJ PasswordSafe.

## Requirements and compatibility

**IntelliJ IDEA 2025.1 or later**, Community or Ultimate/unified editions, running the IDE's Java 21+ runtime. The plugin requires build 251 or later.

| Database | Compatibility exercised |
|---|---|
| MySQL | 5.5.62, 5.6.51, 5.7.44 and 8.4.2 |
| HSQLDB | 2.2.9 through 2.7.4; file, memory and server modes |
| JDBC / Java 8 | Representative Connector/J 5.1, 6.0, 8.0, 8.4 and 9.0; matching HSQLDB releases and Java 8 classifiers |

Choose a JDBC driver compatible with the database server. For HSQLDB, match the server or database file format; Java 8 releases may use the `-jdk8` classifier. Java 8 support applies to drivers/database servers, while the IntelliJ plugin uses Java 21.

The [compatibility report](review/compatibility-2026-10-04.md) records exact SDK builds and the 22 tested server/driver combinations. Every historical patch and arbitrary driver/server pairing has not been tested. IDE UI interaction and dynamic unload testing remain pending.

## Install

1. Download `Lattice-<version>.zip` from this repository's GitHub Releases.
2. In IntelliJ, open **Settings → Plugins → gear menu → Install Plugin from Disk**.
3. Select the ZIP and restart the IDE if prompted.
4. Open the **Lattice** tool window and add a data source.

This release setup publishes to GitHub Releases. JetBrains Marketplace publication is a separate step.

## Connect

Choose MySQL or HSQLDB, then enter the server details or a custom JDBC URL.

The **JDBC driver** selector offers:

- **Bundled**: MySQL Connector/J 9.0.0 or HSQLDB 2.7.3.
- **Download**: choose or enter a release, optionally load **More versions**, then click **Download**.
- **Local JAR**: browse to an existing JDBC driver.

Downloads are explicit and checksum-verified. Driver artifacts are cached in the IDE system directory under `lattice/jdbc`. **Test Connection** shows the actual server product/version after authentication, plus the selected driver's version. A failed authentication/protocol negotiation may prevent version detection.

Generated MySQL URLs use UTC for both the driver and server session. Custom URLs retain your options.

## Build from source

Use a **JDK 21** and the committed Gradle wrapper:

```bash
./gradlew test buildPlugin
```

Windows:

```powershell
.\gradlew.bat test buildPlugin
```

Gradle downloads the configured IntelliJ 2025.1 SDK and creates `build/distributions/Lattice-<version>.zip`. The default version is defined in `gradle.properties`. Use `-PreleaseVersion=1.0.1` to override it.

To use a local SDK:

```powershell
$env:JAVA_HOME = '/path/to/workspace/intellij-extension-test-binaries/ides/2025.1/jbr'
.\gradlew.bat '-Plattice.ide.home=/path/to/workspace/intellij-extension-test-binaries/ides/2025.1' test buildPlugin
```

Standalone packagers are also available. Compile against a **2025.1 SDK** for the release baseline:

```powershell
.\build-plugin.ps1 -IdeaHome 'C:/path/to/idea-2025.1'
# Optional: -Version 1.0.1 -TestBinaries 'C:/path/to/shared-test-binaries'
```

```bash
JAVA_HOME=/path/to/jdk-21 IDEA_HOME=/path/to/idea-2025.1 ./build-plugin.sh
# Optional: VERSION=1.0.1
```

Standalone scripts create `build/Lattice-<version>.zip`. License notices are included by both build paths. See [release instructions](RELEASING.md) for distributing the corresponding MySQL source asset.

## Test assets and functional tests

Reusable SDKs, database servers, JDBC versions and test tools are kept **outside the repository**, by default in the sibling folder:

```text
/path/to/workspace\intellij-extension-test-binaries
```

That folder has its own inventory/startup README. Set `LATTICE_TEST_BINARIES` or pass `-TestBinaries` to use another location. `IDEA_HOME` / `-IdeaHome` override the SDK. Production driver JARs in `lib/` are deliberately retained for plugin distribution.

The live suites require test-only fixtures:

| Engine | Host/port | Database | User | Password |
|---|---|---|---|---|
| MySQL | localhost:3306 | shop_db | root | empty |
| HSQLDB | localhost:9001 | testdb | SA | empty |

These credentials are only for isolated local/CI fixtures. The suites create and remove temporary schemas.

```powershell
.\test-functional.ps1
.\verify-idea-compatibility.ps1
.\test-driver-compatibility.ps1 -Java8Home $env:JAVA8_HOME -Download
```

Gradle splits pure and live tests:

```bash
./gradlew test
./gradlew integrationTest
```

Linux/macOS can run `IDEA_HOME=/path/to/idea ./test-plugin.sh` with the same fixtures. Release tooling uses Python 3.11+ (3.12 recommended):

```bash
python3 -m unittest discover -s scripts/tests -v
```

Temporary compiled classes, reports and logs go into ignored `build/`. [Contributing](CONTRIBUTING.md) covers fixtures, validation and limitations.

## Releases and support

Pushing a stable tag such as `v1.0.1` triggers [the release workflow](.github/workflows/release.yml). It builds and tests with fresh MySQL/HSQLDB fixtures, checks the exact ZIP against three IntelliJ 2025 releases, and publishes it with patch notes, commit history, checksums and corresponding MySQL sources. **Run workflow** performs a dry run without publishing.

See [CHANGELOG](CHANGELOG.md), [RELEASING](RELEASING.md), [issue reporting](ISSUE.md) and [security reporting](SECURITY.md).

## Known limits

HSQLDB multi-statement DDL is not atomic. Stable pagination for a table without a primary key requires an explicit unique order. Partial CREATE reconstruction is labeled when native SCRIPT is unavailable. A connection failure during commit can leave an uncertain server outcome; verify persisted state before retrying.

## License

Lattice source is [MIT licensed](LICENSE). Bundled libraries retain their licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
