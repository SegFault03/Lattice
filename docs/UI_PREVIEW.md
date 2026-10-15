# Swing UI previews

Run the preview helper from the repository root on Windows, Linux or macOS:

```sh
python scripts/ui-preview.py
```

It paints the plugin's production Swing screens with fixture data and a headless IntelliJ test application for platform services such as the Action System. It does not open an IDE window or recreate the screens in separate UI code. The default run uses a light look and feel and refreshes these five tracked README images:

- `screenshots/side-panel.png`
- `screenshots/connection-dialog.png`
- `screenshots/table-view.png`
- `screenshots/table-editing.png`
- `screenshots/sql-console.png`

The five featured images are painted directly at higher pixel density: at least 2×, or a larger integer scale to meet the Marketplace recommendation of 1200 × 760 pixels. Component layout and aspect ratio stay unchanged. The README sets their original display widths (340, 740 and 1100 pixels), with height determined automatically. Upload the same PNGs under `screenshots/` to Marketplace; no separate export is needed. The featured light/dark variants in `--all-previews` also use this resolution; other gallery variants keep their original resolution.

It also writes `build/ui-preview/index.html` and a ZIP of the generated preview set. Open the gallery to inspect the captures before committing changed README images.

Explorer previews use the production native action toolbar. The helper waits for action expansion before painting and fails if a toolbar has no visible actions.

Use JDK 21 and run `./gradlew test` on Linux/macOS or `.\gradlew.bat test` on Windows once to cache the IntelliJ SDK and UI-test runtime dependencies. Missing inputs and checksum-verified FlatLaf are downloaded into the selected `--download-dir` (the OS development cache by default). In this headless Linux environment the preview selects an installed regular sans-serif font to avoid the host's italic logical-font fallback.

On Windows, pass the local Java 21 and IntelliJ SDK paths if they are not already discoverable. The renderer uses Java argument files so SDK and workspace paths with spaces work:

```powershell
python scripts/ui-preview.py --java-home 'C:/path/to/jdk-21' --ide-home 'C:/path/to/IntelliJ IDEA 2025.1'
```

Options:

```sh
python scripts/ui-preview.py --all-previews
python scripts/ui-preview.py --all-previews --output build/ui-after --compare-with build/ui-before
python scripts/ui-preview.py --java-home /path/to/jdk-21 --ide-home /path/to/intellij-sdk
```

`--all-previews` adds light and dark variants to the gallery; `--output` changes generated build output but still refreshes the five README images; `--compare-with` adds a before/after gallery. The helper requires the IntelliJ 2025.1 SDK and JUnit/Hamcrest runtime jars in the selected Gradle cache, or explicit JDK/SDK paths.

These previews show component layout and Swing painting with fixture data. For IDE chrome, live plugin loading, tool-window behavior and database-backed flows, Linux users can use [`capture-intellij-ui.sh`](../scripts/linux/capture-intellij-ui.sh) or [`review-intellij-ui.sh`](../scripts/linux/review-intellij-ui.sh). Windows users can use [`capture-intellij-ui.ps1`](../scripts/windows/capture-intellij-ui.ps1) or [`review-intellij-ui.ps1`](../scripts/windows/review-intellij-ui.ps1) from an interactive desktop session; captures use the primary display, which must be at least 1440×800. Windows capture automatically restores the newly launched `lattice-ui-test` IDE when another application takes the foreground, preserving focus in IDE dialogs and popups. Allow the capture script to use the desktop while it runs; a locked desktop still prevents capture. Their setup and output paths are documented in the [script reference](SCRIPTS.md).

Windows PowerShell examples:

The test applies 100% scaling directly to the IDE VM options as well as the Driver JVM, so Windows display scaling does not offset automated clicks. The fixture Maven project imports offline from its prepared JDBC repository.

For a focused palette review, pass `-StyleOnly -GradleArgs @('-Plattice.ui.lightThemes=true')` to Windows capture. It switches the running IDE through the bundled light themes (including Islands Light when available) and checks the SQL export submenu against the table palette, saving table and console captures for each theme.

```powershell
.\scripts\windows\capture-intellij-ui.ps1 -JavaHome 'C:/path/to/jdk-21' -IdeHome 'C:/path/to/IntelliJ IDEA 2025.1' -InputsOnly
.\scripts\windows\review-intellij-ui.ps1 -JavaHome 'C:/path/to/jdk-21' -IdeHome 'C:/path/to/IntelliJ IDEA 2025.1' -FixtureCache 'C:/path/to/jdbc-fixtures' -InputsOnly -Themes @('ExperimentalDark')
```

Dependency paths and cleanup are described in the [script reference](SCRIPTS.md#binary-paths-downloads-and-cleanup). Windows capture/review accepts `-DownloadDir`, `-BinariesDir`, `-GradleUserHome`, `-BuildIdeHome`, `-Cleanup` and `-NoCleanup`; missing JDK/SDK/JDBC fixtures are provisioned automatically. Standalone previews accept `--download-dir`, `--cleanup`, `--no-cleanup`, `--flatlaf-jar`, `--junit-jar` and `--hamcrest-jar`.

## UI regression coverage

Explorer search has focused Swing previews and real IntelliJ interaction tests:

```bash
python scripts/ui-preview.py --search-only --output build/ui-search-preview
LATTICE_UI_EXPLORER_SEARCH_ONLY=true LATTICE_UI_REVIEW_OUTPUT=build/ui-review/explorer-search ./scripts/linux/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

The focused preview leaves the tracked README images untouched. The IDE test
checks connected sources only, unexpanded column discovery, exclusive All and
combined filters, highlighted matches, refresh, expansion restoration, and both
narrow drawer and wide inline layouts. CI runs each theme independently and
retains its screenshots, runtime evidence and JUnit result under
`build/ui-review/explorer-search/<theme>`. Search matcher, metadata, controller,
renderer and widget unit tests also run in the standard test task on every OS.

The live IDE reviews verify the compact left-hand source picker, SVG sizes and
provider-specific default ports. They create real foreign-key relationships
through the SQL console, then check primary, foreign and combined key badges
and painted header colors in both SQL results and the table editor. Aliased
queries and later ordinary queries verify that key markers follow source columns
and clear when results or schema metadata change.

Busy-state checks hold a real JDBC response at the fixture gate. After priming
the display once (Xvfb can leave a footer unpainted when a modal closes) and
dismissing IDE notification balloons that can cover the footer, they
require natural spinner pixel changes within five seconds. A static spinner
still fails. The capture tracks the live footer bounds after layout; moving the
footer resets its baseline and does not count as animation. First/last samples
and a full display capture are saved on failure, with the small samples and
complete exception details also available in the job log.
The connection scenario deliberately shows a native IDE balloon and verifies
that it is dismissed before checking the explorer's live animation. Balloon
fade-out finishes before the baseline and cannot count as spinner movement.

The console also recovers a session that closes after cancellation validation:
if preparation fails before user SQL is submitted, it reconnects once. SQL that
has started is never retried. Real server tests cover bundled and legacy HSQLDB
drivers, preserve healthy uncommitted work, and verify cancellation still works
after preparation recovery.

The download-progress review gates Maven's real HTTPS response through a local
CONNECT tunnel while checking the progress bar and disabled controls. TLS and
checksum verification remain end-to-end with Maven Central; the tunnel forwards
through the configured session proxy when present. Releasing the gate lets the
normal download and validation complete, avoiding a race with fast transfers.
After switching the resized dialog to JDBC URL mode, the failed-connection
regression dispatches the production Swing button action on the IDE event thread
and requires both the backend's failure status and its error dialog.

Linux CI runs the dark functional review, light styling review and PostgreSQL
review as separate checks after their shared prerequisites pass. Each suite can
fail the job independently, and a failure does not suppress the other suites'
reports. The artifact also retains native IDE logs, thread dumps and screenshots.

## PostgreSQL end-to-end review

```bash
python scripts/postgres-ui-review.py --cleanup --ide-home /path/to/ideaIC-2025.1
```

The wrapper owns a PostgreSQL 18 fixture on a free loopback port and runs the full
IntelliJ scenario in the selected theme. It verifies PostgreSQL driver selection,
authenticated connection, schema/table DDL, grid insertion/edit validation and
rollback, filters/count/pagination, all persisted exports, native CREATE display,
column changes, SQL outcomes and cancellation/recovery. Screenshots and runtime
evidence go to `build/ui-review-postgres`. The existing HSQLDB scenario remains
available through the native review runner.

Every branch push and PR merge build runs this PostgreSQL scenario in Linux CI,
alongside the HSQLDB Dark/Light reviews and Swing assertions. CI reuses the
Gradle compilation SDK (`./gradlew writeUiIdeHome`) and retains both UI report
directories in `tests-ubuntu-24.04`. These are baseline UI checks; the database
and IDE compatibility matrices run only on PRs targeting `staging` or `main`.

For an external **disposable test server**, set `LATTICE_UI_POSTGRES_PORT` and
`LATTICE_UI_POSTGRES_PASSWORD` when invoking the native runner. The scenario
recreates its `public` test schema. Use the wrapper for automatic isolation
and cleanup.
