# Swing UI previews

## Cell inspector interaction

Use **Inspect** in the table toolbar or **Alt+Enter** to toggle the inspector.
Closing it keeps it closed during ordinary cell selection. Clicking a column
header opens column details; pinning retains the inspected value/column while
the selection changes. Reopening restores the divider position.

**Edit value** starts an unstaged draft. While editing, the inspector stays on
that cell. **Stage change** explicitly adds the draft to the pending batch;
**Commit** writes the batch to the database. Commit/reload cannot implicitly
stage an inspector draft. Cancel, X and Escape discard the current inspector
edit; X/Escape also close the inspector. Disposing the editor tab preserves an
unconfirmed draft separately in the existing local project draft store and
restores it in edit mode, outside the commit batch.

Untouched values disable Stage change. SQL NULL, an empty string and a database
default are distinct; use the value menu for explicit state changes. Character
counts follow the draft, and invalid values show feedback beside the editor.
Presentation controls appear for structured/long values, scalar editors remain
compact, and column metadata expands on demand. Metadata and identifiers wrap
and can be selected/copied. Actions retain native theme geometry and keyboard
focus.

The focused `--cell-details-only` previews also exercise closed-state retention,
divider restoration, compact JSON, long metadata, local validation and larger
fonts in both themes. The native focused scenario checks the explicit toolbar
toggle and leaves the inspector closed after an ordinary grid click. See the
[prioritized implementation checklist](design/data-cell-refresh-plan.md).

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

Completed table operations publish a refresh to every open explorer and affected
table editor. Refreshes re-read columns and data and invalidate cached row counts;
metadata reads use auto-commit independently of a console's transaction setting.
Busy views retain the request, and unsaved row drafts defer the grid reload until
commit or revert. SQL console execution refreshes the connection because scripts,
procedures and DML returning rows can affect objects outside the selected schema.
The focused Composer scenario exercises grid insert/update/delete, alter-table,
console DML/DDL, a hidden editor, draft preservation, explorer truncate/drop, and
table counts without pressing Refresh.

Both table dialogs share their header, fixed icon slots, adjacent captions and
footer layout. Provider artwork and its text occupy separate components, so an
SVG that resolves after layout cannot take width from the database name. Primary
and secondary footer actions use native Swing button borders, painting and
sizing; the shared row aligns both heights and text baselines with an 8-pixel
logical gap. Theme color properties preserve green/red emphasis without painting
outside the native rounded surface. SQL document colors follow theme changes.

Regression checks cover late icon loading for every provider, enabled/disabled
and default/non-default actions, matched button edges and baselines, field/title
alignment, metadata baselines, and larger UI fonts and list rows. Resizing checks
cover minimum, preferred and wide layouts. The previous combined source label
and differently sized footer actions both fail the retained reproduction tests.

Inspector actions reserve native text padding and show complete column-action
labels. Their row moves below a long title when needed. Form rows measure wrapped
content at the current viewport width; SQL preview takes the remaining height and
can shrink to three readable lines before the outer form needs to scroll. Resize
checks cross this threshold in both directions. Inspector padding reserves the
current native scrollbar width consistently, preventing IntelliJ's transparent
scrollbar insets from creating a persistent extra line of wrapped text.
Native dialog audits also capture
an 8-logical-pixel height reduction at each width and assert that no outer scrollbar
appears while the form fits.

Focused native reviews accept `-Plattice.ui.scale=1.25` (or `1.5`). Java2D device
scaling stays at 100% so Driver and screenshot coordinates agree. Add
`-Plattice.ui.dialogAudit=true` to the focused create/alter scenario to switch the
live modal through Light/Dark and available Islands counterparts at three widths,
assert its geometry and SQL palette, and retain 12 or 24 additional captures per
dialog (including compact heights). The focused create scenario audits both
create and alter dialogs. The original theme and bounds are
restored before the database scenario continues. For example:

```bash
./scripts/linux/capture-intellij-ui.sh -Plattice.ui.review=true -Plattice.ui.createTableOnly=true -Plattice.ui.dialogAudit=true -Plattice.ui.ide.home=/path/to/idea-2026.2.3 -Plattice.ui.scale=1.25
```

### Column Composer

The create-table dialog uses the selected Column Composer design: a persistent
column navigator, table name, properties and a complete SQL preview. Add/remove
and move controls update the same definition, including composite primary keys,
identity columns, nullability, defaults and decimal precision/scale. Filtering
and navigation retain every draft, including invalid input. Create stays disabled
until the full definition and provider-specific SQL are valid.

Creation runs on an owned background connection inside the dialog. Busy controls
prevent duplicate submissions and closing during execution. Database errors keep
the complete form available for correction and retry; successful creation closes
the dialog and refreshes the explorer. Closing a changed definition requires a
discard decision. The immutable preview snapshot supplies execution values.

```bash
LATTICE_UI_CREATE_TABLE_ONLY=true LATTICE_UI_REVIEW_OUTPUT=build/ui-review/create-table ./scripts/linux/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

Direct Gradle/capture users pass `-Plattice.ui.review=true` and
`-Plattice.ui.createTableOnly=true`. The focused scenario owns an HSQLDB fixture,
creates a six-column table using the real UI, then checks JDBC column order,
identity/primary key metadata, precision and working defaults. It also exercises
hidden invalid drafts, duplicates, add/remove/reorder, cancellation, animated busy
feedback, database failure and successful retry. Results include
`create-composer-dialog.png`, `create-composer-full-ide.png`, busy/error captures
and `runtime-evidence.txt`. It also verifies automatic explorer/grid refresh after
grid insert/update/delete, alter-table changes, console DML/DDL and explorer
truncate/drop, including deferred refresh while an unsaved row draft is present.
Main PR CI runs this scenario with `LATTICE_UI_DIALOG_AUDIT=true` and
`LATTICE_UI_SCALE=1.25` on the pinned build SDK. Embedded HSQLDB/H2 tests compare preview and executed
CREATE statements and verify generated data and composite-key enforcement.

### Column Inspector

The edit table dialog stages a batch across its column navigator and property
inspector. Users can add several columns, change properties and names together,
drop columns and rename the table before applying. Navigation and filtering retain
all drafts, including invalid input. New and dropped rows remain visible; Revert
resets a column, Restore cancels a pending drop, and removing a new draft never
issues DDL. Closing or reloading a dirty dialog requires a discard decision.

Apply is enabled only after every draft is valid and the complete batch has a SQL
preview. Duplicate final names are checked across all drafts. Drops free names
first, property changes precede column renames, rename chains run from their free
end, additions follow, and table rename runs last. Rename cycles require an
intermediate name in a separate Apply. Preview work only reads metadata off the
EDT; the same dialect builders supply execution SQL, including preserved MySQL
attributes and combined modify/rename definitions on MySQL 5.

Before writing, Apply verifies the entire schema snapshot and reviewed SQL.
PostgreSQL executes the batch in one transaction. MySQL, HSQLDB and H2 can commit
DDL incrementally: execution stops at the first failure, reloads actual metadata,
and rebases remaining drafts so successful additions, renames and drops are not
replayed. A failed metadata refresh blocks Apply until an explicit reload.

The focused real-IDE scenario uses an owned, disposable HSQLDB server. One Apply
combines a same-column rename and modification, another column's type change,
two additions, a drop and table rename. It verifies JDBC metadata and retained
data, hidden invalid drafts, filtering, close/discard handling, destructive
cancellation and animated busy feedback. A second batch deliberately fails after
several committed operations, then fixes and retries the retained remainder.

```bash
LATTICE_UI_ALTER_TABLE_ONLY=true LATTICE_UI_REVIEW_OUTPUT=build/ui-review/alter-table ./scripts/linux/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

Direct capture/Gradle users can pass `-Plattice.ui.review=true` and
`-Plattice.ui.alterTableOnly=true`; the capture requires the documented Maven
fixtures. Results include `alter-inspector-full-ide.png`, the live modal crop
`alter-inspector-dialog.png`, busy/error evidence and `runtime-evidence.txt`.
These are actual IntelliJ desktop captures, with no replacement look and feel.
The standalone Swing gallery also includes the inspector's normal and drop
states. Unit tests cover editor state and invalid/stale previews; embedded
database tests verify that preview is read-only and execution uses its statements.

If the host restricts home-directory writes, point the Java preferences and
runtime caches to writable directories using JVM `java.util.prefs.userRoot`,
`java.util.prefs.systemRoot`, and the platform's cache settings before running.

Table tab source badges are included in the full light/dark preview gallery used
by CI. Rendering checks the standard 16 × 16 icon size, bottom-left placement and
opaque brand colors that hide the table grid. Provider unit tests cover every
configured database type, tables and views, caching, and unrelated file icons.
For a focused review without updating README images:

```bash
python scripts/ui-preview.py --tab-icons-only --output build/ui-tab-icons
```

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

For pull requests targeting `main`, Linux CI runs the dark functional review,
light styling review, search reviews, PostgreSQL review, mixed alter/recovery
and a 125% create/alter dialog audit as separate checks
after their shared prerequisites pass. Each suite can
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

Only pull requests targeting `main` run this PostgreSQL scenario in Linux CI,
alongside the HSQLDB Dark/Light reviews and Swing assertions. Pushes, release
tags, manual workflow runs and PRs to other branches skip UI suites and display
setup. Embedded functional tests and plugin packaging still run on every push and PR. CI reuses the
Gradle compilation SDK (`./gradlew writeUiIdeHome`) and retains both UI report
directories in artifacts named by suite and theme. The database
and IDE compatibility matrices run only on PRs targeting `main`.

For an external **disposable test server**, set `LATTICE_UI_POSTGRES_PORT` and
`LATTICE_UI_POSTGRES_PASSWORD` when invoking the native runner. The scenario
recreates its `public` test schema. Use the wrapper for automatic isolation
and cleanup.
