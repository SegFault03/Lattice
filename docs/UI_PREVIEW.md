# Preview and refine the UI without launching IntelliJ

The reusable preview helper renders the production side-panel widgets, connection dialog, table editor/viewer and SQL console into PNGs, a browser gallery, and a shareable ZIP. Use it while changing spacing, grouping, themes, and validation appearance. It compiles the current Java source on every run, so rerun it after each UI edit.

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

Explicit paths are also supported; quote paths containing spaces:

```text
python scripts/ui-preview.py --java-home "/path/to/jdk-21" --ide-home "/path/to/idea-2025.1"
```

On macOS, `--ide-home` points to the SDK's `Contents` directory and `--java-home` to the JDK's `Contents/Home` directory. The first run in a new output directory downloads the pinned, checksum-verified FlatLaf 3.5.4 JAR from Maven Central. The dependency is reused on subsequent runs and is only used by the preview helper.

## View and share

Open `build/ui-preview/index.html` in a browser. No web server is required.

- Theme selects light or dark renders.
- Screen filters the gallery to the side panel, connection dialog, table editor/viewer or SQL console.
- Click a screenshot to open it at full resolution.
- With a baseline supplied, View switches between Current and Before / after.

The helper writes `build/ui-preview/lattice-ui-previews.zip` containing the gallery, its current screenshots, any comparison images, and these instructions. Extract the ZIP and open `index.html` to review it on another machine. Galleries, comparison baselines, compiled classes, SDKs and JARs stay under ignored `build/`.

## Refresh the README screenshots

Every successful run also writes all current light/dark PNGs into the repository's tracked `screenshots/` folder. This happens even with a custom `--output`: that option changes the gallery/build location, while `screenshots/` always represents the latest render. Keep build output outside `screenshots/`.

The five README links use fixed filenames, refreshed from these variants:

| README image | Source variant |
|---|---|
| `screenshots/side-panel.png` | `light-side-panel-340.png` |
| `screenshots/connection-dialog.png` | `light-connection-mysql-600.png` |
| `screenshots/table-view.png` | `light-table-1100.png` |
| `screenshots/table-editing.png` | `light-table-new-row-1100.png` |
| `screenshots/sql-console.png` | `light-sql-console-1100.png` |

Run `python scripts/ui-preview.py` after changing the UI, review the gallery, then commit the changed PNGs alongside the Java/helper changes. Do not rename the five featured files or manually update their contents. Their mapping lives in `FEATURED_SCREENSHOTS` in `scripts/ui-preview.py`; a missing featured variant fails generation before existing README images are replaced. Commit the other light/dark variants too, so all preview states remain available in the repository.

## Iterate with before/after previews

Capture a baseline before editing:

```text
python scripts/ui-preview.py --output build/ui-before
```

Make the UI changes, then render into a different directory and supply the baseline:

```text
python scripts/ui-preview.py --output build/ui-after --compare-with build/ui-before
```

Open `build/ui-after/index.html` and select Before / after. Matching variants appear beside one another; new variants show only the current screenshot. Repeat the second command after each edit. `--compare-with` must reference an existing directory different from `--output`.

For the current UI-fixes review, the baseline is in `build/ui-preview-before`, so regenerate the comparison with:

```text
python scripts/ui-preview.py --compare-with build/ui-preview-before
```

Local baseline directories are not committed. A fresh checkout can make its own baseline using the commands above. The helper reads a fresh render manifest, so stale images from previous runs are not included in the new gallery or ZIP.

## Coverage and limitations

The 56 variant screenshots cover light/dark themes; populated/empty side panels; SQL consoles at 1100, 760 and 520 pixels with sample queries/results, empty results, execution and error messages; table widths of 1100, 760, and 520 pixels; populated table/view fixtures; modified cells; valid new rows; and invalid decimals with Commit disabled. Hover previews show NULL tooltips in both grids and modified-cell context in the table editor. Connection variants include MySQL, HSQLDB server/file/memory, custom JDBC URL, bundled/downloaded/local drivers, a wider dialog, an enlarged download form, and a scrolled view of its driver controls. Five additional stable copies are featured in the README.

The helper uses the actual layout managers, SDK icons, controls, borders, table model, cell renderers, and validation states. Fixture checks exercise Set NULL / Use Default through the relocated context-menu actions, verify identity cells are read-only, confirm valid/invalid Commit states, check narrow toolbar/footer bounds, and catch leftover renderer outlines and empty HSQL form rows. Both grids are checked for distinct header/data backgrounds and readable NULL tooltips. Console checks exercise repeated template insertion, history restoration, Clear, the Run shortcut with selected SQL, and execution-control states; background work stays blocked.

The Swing controls in the gallery are images; only gallery selectors are interactive. FlatIntelliJLaf and FlatDarculaLaf approximate IntelliJ themes. Tooltip previews paint an actual Swing `JToolTip` at the production grid's computed cell anchor; real popup placement near screen edges still needs an IDE check. Dialog title, outer padding and Cancel/OK buttons are simulated. The explorer uses a Swing `JTree` with Lattice's production node renderer because IntelliJ's `Tree` needs application services. Exact IDE window sizing, tree styling, display scaling, focus, keymaps, clipboard access, file browsing, and live database behavior still need an IDE check.

The helper compiles production classes into its own output directory, then places preview-only `DatabaseTaskService` and `DialogWrapper` shadows ahead of them on the preview classpath. JDBC/background work is blocked. No IDE application container, database connection, driver download, or connection test is started. Reflection supplies sample rows and selects preview states in the existing private table model. The explorer preview assembles the same production toolbar, tree renderer, empty state and hint used by `DatabaseMainPanel`, replacing its project-service orchestration with a fixture tree. The SQL console uses its production panel and result model; headless rendering uses Ctrl as the menu shortcut modifier.

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

The connection dialog has Data source, Connection, Authentication, JDBC URL, and Driver sections. Nested cards size to visible content, so file/memory modes do not reserve blank server fields. Bundled drivers show one summary; Driver options expands the advanced controls and opens automatically for download/local-JAR configurations. Long forms scroll while dialog actions remain fixed. The URL uses the available width, keeps its full value in a tooltip, and has a copy action.

Both database grids now share a tinted header band, theme-scaled row/header heights, numeric alignment and cell-based tooltips. A tooltip appears directly below its hovered cell using table coordinates, including when scrolled, sorted or reordered. NULL, empty strings, database defaults, identity keys, pending changes and validation errors have explicit descriptions. Database text is escaped before display, so values containing HTML-like text cannot become empty or formatted tooltips. IntelliJ's clipped-cell expansion popup is disabled for these grids to avoid competing hover surfaces.

The SQL console groups database selection and Run/Stop/Clear actions in the first strip, with History/Template below it. Labels and fields wrap together at narrow widths. The query section has a scrolling line-number gutter and a platform-appropriate shortcut hint. Results use the same grid styling as the table editor; an empty state prompts the first query. The footer holds execution status and Max rows. Database/limit controls stay disabled during execution, while completed/error states restore them. Choosing the same template twice inserts it twice.
