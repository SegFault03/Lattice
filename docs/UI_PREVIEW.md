# Preview and refine the UI without launching IntelliJ

The reusable preview helper renders production UI components into PNGs, a browser gallery, and a shareable ZIP without launching IntelliJ. By default it renders only the five featured README screens in the light theme. Pass `--all-previews` to render the full light/dark UI review set, including the welcome screen, side-panel states, dialogs, table variants and SQL console states. Use it while changing spacing, grouping, themes, and validation appearance. It compiles the current Java source on every run, so rerun it after each UI edit.

## Setup and first run

Run commands from the repository root, the directory containing `gradlew`, `build.gradle.kts`, and `scripts/`.

Requirements:

- Python 3.11 or newer (`python3` can be used instead of `python`).
- A full JDK 21, including `java` and `javac`. Set `JAVA_HOME`, put the JDK on PATH, or pass `--java-home`.
- The IntelliJ 2025.1 SDK. The helper discovers Gradle's cached `ideaIC-2025.1` SDK and respects `GRADLE_USER_HOME`. An installed SDK can instead be selected with `--ide-home`.

If the SDK is not cached, download it through the existing build first:

```sh
# Linux / macOS
./gradlew compileJava
```

```powershell
# Windows
.\gradlew.bat compileJava
```

Then generate previews on any platform:

```text
python scripts/ui-preview.py
```

This default run renders the five featured screens. To generate the full light/dark preview gallery instead:

```text
python scripts/ui-preview.py --all-previews
```

Explicit paths are also supported; quote paths containing spaces:

```text
python scripts/ui-preview.py --java-home "/path/to/jdk-21" --ide-home "/path/to/idea-2025.1"
```

On macOS, `--ide-home` points to the SDK's `Contents` directory and `--java-home` to the JDK's `Contents/Home` directory. The first run in a new output directory downloads the pinned, checksum-verified FlatLaf 3.7.2 JAR from Maven Central. The dependency is reused on subsequent runs and is only used by the preview helper.

## View and share

Open `build/ui-preview/index.html` in a browser. No web server is required.

- Theme selects light or dark renders.
- Screen filters the gallery to the welcome screen, input controls, the side panel, connection dialog, schema dialogs, table editor/viewer or SQL console.
- Click a screenshot to open it at full resolution.
- With a baseline supplied, View switches between Current and Before / after.

The helper writes `build/ui-preview/lattice-ui-previews.zip` containing the gallery, its current screenshots, any comparison images, and these instructions. Extract the ZIP and open `index.html` to review it on another machine. Galleries, comparison baselines, compiled classes, SDKs and JARs stay under ignored `build/`.

## Refresh the README screenshots

Every successful run refreshes only the five fixed README images in the tracked `screenshots/` folder and removes any extra PNGs there. This also applies with `--all-previews` and a custom `--output`: all generated variants stay in the gallery/build directory, while `screenshots/` remains limited to its five featured files. Keep build output outside `screenshots/`.

The five README links use fixed filenames, refreshed from these variants:

| README image | Source variant |
|---|---|
| `screenshots/side-panel.png` | `light-side-panel-340.png` |
| `screenshots/connection-dialog.png` | `light-connection-mysql-600.png` |
| `screenshots/table-view.png` | `light-table-1100.png` |
| `screenshots/table-editing.png` | `light-table-new-row-1100.png` |
| `screenshots/sql-console.png` | `light-sql-console-1100.png` |

Run `python scripts/ui-preview.py` after changing the featured UI screens, review the gallery, then commit changed files alongside the Java/helper changes. Use `python scripts/ui-preview.py --all-previews` when reviewing other states or making a full before/after comparison. Do not rename the five featured files or manually update their contents. Their mapping lives in `FEATURED_SCREENSHOTS` in `scripts/ui-preview.py`; a missing featured variant fails generation before existing README images are replaced. Full preview variants are generated under ignored `build/` output and are not committed.

## Iterate with before/after previews

Capture a baseline before editing:

```text
python scripts/ui-preview.py --all-previews --output build/ui-before
```

Make the UI changes, then render into a different directory and supply the baseline:

```text
python scripts/ui-preview.py --all-previews --output build/ui-after --compare-with build/ui-before
```

Open `build/ui-after/index.html` and select Before / after. Matching variants appear beside one another; new variants show only the current screenshot. Repeat the second command after each edit. `--compare-with` must reference an existing directory different from `--output`.

For the current copy and welcome-screen review, the local baseline is in `build/ui-copy-welcome-before`, so regenerate the comparison with:

```text
python scripts/ui-preview.py --all-previews --compare-with build/ui-copy-welcome-before
```

Local baseline directories are not committed. A fresh checkout can make its own baseline using the commands above. The helper reads a fresh render manifest, so stale images from previous runs are not included in the new gallery or ZIP.

## Coverage and limitations

The default run produces exactly five screenshots: the side panel, MySQL connection dialog, table viewer, new-row table editor, and SQL console. `--all-previews` generates the full 106-variant set in light and dark themes, covering shared input controls (including editable, disabled/error and larger-font states); create-database/create-table/alter-table dialogs and the drop-column warning; welcome screens at 900 and 520 pixels, with and without saved connections; populated/empty/error side panels and the first-connection database menu; SQL consoles at 1100, 760 and 520 pixels with sample queries/results, empty results, execution and error messages; table widths of 1100, 760, and 520 pixels; populated table/view fixtures; modified cells; valid new rows; an active inline cell editor; and invalid decimals with Commit disabled. Native-delegate table/console variants exercise IntelliJ button, header and tab painting; auto-column variants show editing existing identities and inserting explicit new values. Connection variants include new/edit connection titles, success/failure feedback, MySQL, HSQLDB server/file/memory, custom JDBC URL, bundled/stored-download/local drivers for both databases, a wider dialog, an enlarged download form, and a scrolled view of its driver controls. Expanded bundled-driver cases cover both databases and all HSQLDB modes; a collapse-after-expansion case checks shrinking, and incomplete MySQL/HSQLDB/JDBC forms show disabled testing. Only the five featured README images are stored in `screenshots/`; other generated variants stay in ignored build output.

The helper uses the actual layout managers, SDK icons, controls, borders, table model, cell renderers, and validation states. Each render checks visible single-line inputs for consistent preferred height, symmetric vertical insets and text-baseline balance within two pixels, including composite browse fields. Fixture checks exercise Set to NULL / Use database default through the relocated context-menu actions, verify identity cells are editable with validated values and original row keys preserved, confirm valid/invalid Commit states, check narrow toolbar/footer bounds, and catch leftover renderer outlines and empty HSQL form rows. Both grids are checked for distinct header/data backgrounds and disabled hover surfaces. Native header tests inspect painted pixels after theme/model changes. Console checks exercise repeated template insertion, history restoration, Clear, the Run shortcut with selected SQL, and execution-control states; background work stays blocked. Welcome checks verify console availability after adding a connection and the positive startup preference; status checks verify that severity icons reset. Explorer checks verify error diagnostics and tooltip reset.

The Swing controls in the gallery are images; only gallery selectors are interactive. FlatIntelliJLaf and FlatDarculaLaf approximate IntelliJ themes. The `*-native-1100.png` grid/console renders use SDK Darcula button, header and tab delegates with the preview theme defaults; other widgets still use FlatLaf. Table bodies and headers have no hover popups or highlighting. Dialog title, outer padding and footer action buttons are simulated using their production labels and action selection. The explorer uses a Swing `JTree` with Lattice's production node renderer because IntelliJ's `Tree` needs application services. Exact IDE window sizing, tree styling, display scaling, focus, keymaps, clipboard access, file browsing, and live database behavior still need an IDE check.

The helper compiles production classes into its own output directory, then places preview-only `DatabaseTaskService` and `DialogWrapper` shadows ahead of them on the preview classpath. JDBC/background work is blocked. No IDE application container, database connection, driver download, or connection test is started. Reflection supplies sample rows and selects preview states in the existing private table model. The explorer preview assembles the same production toolbar, tree renderer, empty state and hint used by `DatabaseMainPanel`, replacing its project-service orchestration with a fixture tree. The SQL console uses its production panel and result model; headless rendering uses Ctrl as the menu shortcut modifier. Connection outcomes use fixture messages through the shared production status helper; no connection test is executed.

Sources live in `scripts/ui-preview.py` and `scripts/ui-preview/`, outside Gradle's production/test source sets. Their dependency, shadows, and artifacts are excluded from the plugin ZIP.

## Design references and implemented changes

The reference is JetBrains' built-in **Database Tools and SQL** plugin:

- [Data editor and viewer](https://www.jetbrains.com/help/idea/data-editor-and-viewer.html): compact grouped toolbar, a separate WHERE / ORDER BY strip, pagination controls, and cell-specific context menus.
- [Data Sources and Drivers dialog](https://www.jetbrains.com/help/idea/data-sources-and-drivers-dialog.html): connection details and authentication grouped independently from driver settings, with connection testing beside the dialog actions.
- [Database tool window](https://www.jetbrains.com/help/idea/database-tool-window.html): native glyphs, subtle separators, and small toolbar actions, also consistent with Lattice's refactored side panel.
- [Query consoles](https://www.jetbrains.com/help/idea/query-consoles.html): a clear query/results split and separate result/output tabs.
- [DBeaver SQL editor](https://dbeaver.com/docs/dbeaver/SQL-Editor/): execution actions beside the editor, a separate result panel, and distinct grid headers.

Reviewed the official documentation and screenshots on 2026-10-07. Lattice follows these structural conventions while retaining its existing database operations and configuration model.

The table editor now separates reload, row edits, pending changes, and export/console/options into toolbar groups. WHERE and ORDER BY controls wrap as complete groups, and pagination sits next to the bottom status. Count Rows and Truncate are in Table options; Set NULL and Use Default are in the cell context menu. Table rows have theme-scaled height, numeric alignment, quieter grid/header rules, and restrained modified/new-row cues. Validation errors and keyboard focus remain visible.

The connection dialog has Data source, Connection, Authentication, JDBC URL, and Driver sections. Nested cards size to visible content, so file/memory modes do not reserve blank server fields. Driver options expands the advanced controls and opens automatically for download/local-JAR configurations and for connections already using a stored driver. The bundled list holds the packaged driver followed by every release downloaded on this machine, each labelled with its origin; stored releases are also offered first in the download list, and a finished download joins the bundled list immediately. Changing modes or collapsing options resizes the dialog in both directions. Long forms scroll while dialog actions remain fixed. The URL uses the available width, keeps its full value in a tooltip, and has a copy action.

Test connection stays disabled until the active form is ready. Server forms need a host, valid port and user; HSQLDB also needs its database name or file path. MySQL's database and passwords may be empty. JDBC URL mode needs a full `jdbc:` URL; credentials may be supplied by that URL. A selected local JAR must exist and be readable, and a downloaded driver must already be available. Driver downloads remain enabled independently of testing. The disabled action's tooltip explains the missing requirement. Readiness updates as fields, modes and driver sources change, including after background tasks finish.

Both database grids share a tinted header band, theme-scaled row/header heights and numeric alignment. Column widths use rendered headers and sampled values, bounded from 72 to 320 logical pixels, rather than fixed character estimates. The band derives its color from the current data background and remains distinct after native UI refreshes. Table bodies and headers disable tooltips, clipped-cell expansion and native hover highlights; toolbar and footer diagnostic tooltips remain available. NULL/default values and pending/invalid edits retain their inline text and color cues. Auto-generated columns are labeled `Auto` and can be edited in existing and new rows. New automatic values use an explicit default marker, which survives opening an editor unchanged and can be restored with Use database default. Manual values undergo the same type/range validation as other columns. Updates match the original primary key; the database may still reject values for GENERATED ALWAYS columns. Views and SQL result grids remain read-only.

The SQL console groups database selection and Run/Stop/Clear actions in the first strip, with History/Template below it. Labels and fields wrap together at narrow widths. Toolbar actions size to their icon/text instead of native dialog-button minimum widths, and grouped labels align to input baselines. Database and History share a label width; toolbar strips use consistent leading spacing. The query section has a scrolling line-number gutter, a wrapping shortcut caption and a platform-appropriate shortcut hint. The initial editor/results split is 33%/67% and remains draggable. Results/Messages use standard tab titles so native themes paint visible labels beside the selected-tab indicator. Results use the same grid styling as the table editor; an empty state prompts the first query. The footer holds execution status and Max rows. Database/limit controls stay disabled during execution, while completed/error states restore them. Choosing the same template twice inserts it twice.

## Shared input design

Use `DatabaseInputs` from `com.segfault03.ideadb.ui` for every single-line input. Its factories create text/password fields, dropdowns, editable dropdowns and browse fields with the same outline and sizing rules. The connection and schema dialogs, table WHERE/ORDER BY fields, refresh/page-size pickers, SQL-console pickers and table cell editors all use it.

- Minimum height: **26 logical pixels**, growing to fit the current font plus six pixels of vertical space; all dimensions respect IDE scaling.
- Border insets: **3 pixels above/below**, **8 pixels left/right**. Text/password fields suppress additional native margins and restore the shared padding after theme changes; native dropdown spacing remains intact.
- Rounded outline: **8-pixel arc diameter**, with native theme colors and visible focus, error, warning and disabled states.
- Browse fields use the same text outline plus a separate rounded folder button, spaced six pixels away. Native file-chooser actions and keyboard access remain intact. Table editors fit their existing row height and preserve native typed conversion, validation, commit and cancellation.
- Multiline SQL/document editors retain top-aligned text and their existing document padding.

Use `DatabaseInputs.textField(...)`, `passwordField()`, `comboBox(...)` or `browseField()` when adding controls. Set widths separately where the layout calls for them; do not set fixed heights or add another input border. For an existing `JTable`, call `DatabaseInputs.styleTableEditors(table)` to retain its typed editors while applying the shared appearance. Avoid changing global `UIManager` defaults: this style is scoped to Lattice controls.

Run `python scripts/ui-preview.py --all-previews` and choose **Input controls**, **Schema dialogs**, or **Table editor / viewer** in the gallery to review shared fields, schema warnings and inline editing. Font-growth and look-and-feel-refresh tests protect sizing; a native Darcula delegate test compares text/password caret origins; browse tests check button actions and disabled state. Numeric-editor tests protect invalid-value rejection, typed commits and cancellation; default-marker tests protect unchanged Auto cells. An owned in-memory HSQLDB test verifies generated/explicit identity inserts and identity updates matched by the original key. Connection fixtures check compact driver-section gaps, the stored driver appearing and staying selected in the bundled list across a refresh, restored content height after collapse and live Test connection availability; Java tests cover readiness across connection/driver modes, stored-driver reuse without downloading, version ordering and cache scanning.

## Copy, icons and warning cues

See [the UI copy review](UI_REVIEW.md) for the audit and conventions. Use `DatabaseUi.status(label, text, tone)` for operation feedback so severity is conveyed by an icon and text as well as color. Keep footer messages short; put full error details in a tooltip and the Messages tab or native error dialog. Use `DatabaseUi.confirmDestructive(...)` for database deletion confirmations with an explicit action and Cancel.

The Welcome gallery filter includes first-use and configured states. These are actual `WelcomePanel` instances with an isolated settings object. Startup preference interactions change that fixture only. The connection and schema filters now include new-connection button labels, test feedback and the drop-column warning.
