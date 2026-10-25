# Swing UI previews

## Cell inspector interaction

Use **Inspect** in the table toolbar or **Alt+Enter** to toggle the inspector.
Closing it keeps it closed during cell and header selection. A single column-header
click selects and highlights that header; column details follow only while Inspect
is on. Inspect opens the selected header or data cell. Selecting a data cell while
the inspector is open returns the panel to that cell. The inspector overlays the right edge of the grid
without changing grid bounds or column widths. Its body owns its side padding so
IDE scroll-pane border cleanup cannot remove the gutters.
Only one body cell or column header can be selected at a time. Header selection
also clears the former body cell's focus position so it cannot retain an outline.

**Edit value** starts an unstaged draft. While editing, the inspector stays on
that cell. **Stage change** explicitly adds the draft to the pending batch;
**Commit** writes the batch to the database. Commit/reload cannot implicitly
stage an inspector draft. Cancel, X and Escape discard the current inspector
edit; X/Escape also close the inspector. Disposing the editor tab preserves an
unconfirmed draft separately in the existing local project draft store and
restores it in edit mode, outside the commit batch.

Untouched values disable Stage change. SQL NULL, an empty string and a database
default are distinct. Edit, Set default and Copy share one action row. Set default
uses the database default for new rows and is disabled when unavailable; an empty
string can be entered through Edit. NULL and Revert appear when applicable. Character
counts follow the draft, and invalid values show feedback beside the editor.
Unavailable defaults have a deep grey surface with white text. The value viewport
keeps its dimensions across formatted/raw viewing, editing and cancellation;
the surrounding body scrolls when space is limited. Buttons show a focus cue
for keyboard navigation, while mouse clicks leave no persistent focus outline.
Compact presentation controls appear for structured/long values, with an underline
on selected options. Stage change retains readable text when disabled; Cancel has
a red surface and text. Column metadata stays
visible, with compact Edit table below the structure hint. The column name is
the title; the subtitle contains only row and column coordinates. Fixed header
actions keep the close target in a fixed rail. Rounded inspector buttons retain Swing
keyboard and accessibility behavior across themes and platforms. Body/value text
uses the IDE label font size, and actions use its button font size; the inspector
does not enlarge normal text. Header controls occupy real layout space so their
complete hover targets fit at all scales. Action targets have a compact
28-logical-pixel minimum with two-pixel vertical padding. The editor/action gap
is eight pixels, metadata rows use four-pixel vertical padding, and the footer
uses symmetric 12-pixel padding. Metadata and identifiers wrap and can
be selected/copied.

The focused `--cell-details-only` previews also exercise closed-state retention,
overlay placement and grid sizing, compact JSON, long metadata, local validation and larger
fonts in both themes. The manual gallery includes approved date fixtures and narrow viewports.
The desktop-text samples render directly at 125% with smoothing hints supplied on
Swing components while the incoming graphics has text smoothing off. This catches
custom button painters that bypass the IDE's per-component font rendering hints;
the ordinary preview's global smoothing can otherwise hide that defect.
The native focused scenario checks that header and cell clicks keep a closed inspector closed. See the
[prioritized implementation checklist](design/data-cell-refresh-plan.md).

All preview modes assert inspector-only draft recovery through the production
table-editor constructor, including initially missing metadata, metadata failure,
close/reopen, explicit staging and cancellation. Unconfirmed recovered text must
survive grid-state persistence without becoming eligible for Commit.

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

## UI release coverage

Release gates check whether users can reach controls and safely connect, create,
alter, edit, commit, revert, export, cancel and recover work. They retain native
checks for clipped action labels and inaccessible controls, including 125% scale.
Exact colors, pixel padding, font sizes, rounded corners, icon dimensions and
approved-prototype comparisons are not release blockers. Screenshot galleries
remain available for manual design reviews.

`python scripts/ui-preview.py --checks-only` exercises production-constructor
recovery before metadata arrives, failed metadata loading, close/reopen, explicit
staging and cancellation. It does not generate screenshots or update the gallery.
Main PR CI uses this mode instead of the full light/dark gallery.

The native inspector case runs once at 125%, switches through both themes, and
checks selection following, explicit editing and unconfirmed draft recovery.
The standalone 100% duplicate and styling-only CI case have been removed. H2 and
search each run once in dark mode. Create/alter and partial-DDL recovery retain
separate cases because they exercise different destructive-operation paths.

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

Table tab source badges can be inspected in the manual preview gallery.
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
narrow drawer and wide inline layouts. CI runs the dark theme once and
retains its screenshots, runtime evidence and JUnit result under
`build/ui-review/explorer-search/<theme>`. Search matcher, metadata, controller,
renderer and widget unit tests also run in the standard test task on every OS.

The live IDE reviews verify source switching and provider-specific default
ports. Composite key and alias metadata contracts are covered by backend/JDBC
tests; native badge colors and exact icon dimensions are no longer release gates.

Busy-state checks hold a real JDBC response at the fixture gate, require visible
operation feedback and verify that grid mutation controls are disabled until the
response is released. Screenshots remain failure diagnostics. Spinner class,
pixel animation and simulated IDE-notification overlays are not tested.

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
search review, PostgreSQL review, mixed alter/recovery,
a 125% create/alter flow, and one cell-inspector review at 125%
as separate checks
after their shared prerequisites pass. Each suite can
fail the job independently, and a failure does not suppress the other suites'
reports. The artifact also retains native IDE logs, thread dumps and screenshots.

The cell-inspector job sets `LATTICE_UI_CELL_DETAILS_ONLY=true` and requires both
light and dark theme checks. It closes an editor with an unconfirmed inspector
draft, refreshes the explorer to replace its metadata, and reopens the editor.
`inspector-recovered-draft.png` and the runtime report record recovery with Stage
available after metadata loading and Commit disabled until explicit staging.
Reproduce the inspector job with:

```bash
LATTICE_UI_CELL_DETAILS_ONLY=true LATTICE_UI_SCALE=1.25 LATTICE_UI_REVIEW_OUTPUT=build/ui-review/cell-inspector-scaled ./scripts/linux/review-intellij-ui.sh ExperimentalDark
```

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
