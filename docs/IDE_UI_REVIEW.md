# Real IntelliJ UI review

Review date: 2026-10-08. Production UI changes are outside this review; only the capture infrastructure and documentation are changed.

## Result

**SUCCESS: real-IDE capture and review completed.** Seven bundled themes each completed the 70-state capture flow: **490 PNGs**, all 1920×1080, with **zero JUnit failures/errors** in the saved results. Passing the capture flow does not mean the visual issues below are resolved.

| Requested/live theme | PNGs | Saved JUnit result |
|---|---:|---|
| Dark / ExperimentalDark | 70 | [Passed](../build/ui-review/ExperimentalDark/test-result.xml) |
| Light / ExperimentalLight | 70 | [Passed](../build/ui-review/ExperimentalLight/test-result.xml) |
| Light with Light Header / ExperimentalLightWithLightHeader | 70 | [Passed](../build/ui-review/ExperimentalLightWithLightHeader/test-result.xml) |
| High Contrast / JetBrainsHighContrastTheme | 70 | [Passed](../build/ui-review/JetBrainsHighContrastTheme/test-result.xml) |
| Darcula / Darcula | 70 | [Passed](../build/ui-review/Darcula/test-result.xml) |
| IntelliJ / IntelliJ | 70 | [Passed](../build/ui-review/IntelliJ/test-result.xml) |
| IntelliJ Light / JetBrainsLightTheme | 70 | [Passed](../build/ui-review/JetBrainsLightTheme/test-result.xml) |

[Screenshot index](../build/ui-review/index.md). I personally inspected all states across all themes using contact sheets derived from the original desktop PNGs, and opened representative originals to examine dialog geometry, progress, invalid/changed cells, narrow/wide editors, menus, console feedback and High Contrast painting. Analytical contact sheets are under `build/ui-review-inspection`; original PNGs are preserved untouched.

## Repeat the review

```sh
./scripts/review-intellij-ui.sh
```

For a shorter pass, supply theme IDs:

```sh
./scripts/review-intellij-ui.sh ExperimentalDark ExperimentalLight
```

Requirements: Linux, JDK 21, Xvfb, xfwm4 or Openbox, curl and Python 3. Gradle downloads the pinned IDEA if needed. The wrapper discovers `/workspace/.jdk21` in this cloud environment when JAVA_HOME is unset. Network access is required to download genuine Maven JDBC fixtures and exercise a genuine MySQL driver download. Existing proxy settings and CA trust are preserved.

The script prepares an isolated small Maven repository under `build/ui-test-maven/repository`. It builds the production plugin through the existing Gradle project, installs its ZIP into IDEA, and runs JetBrains Starter/Driver automation. The generated project and IDE sandbox are disposable; saved personal connections are not used. Test dependencies stay in `uiTest`.

The equivalent per-theme command is:

```sh
LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" \
  ./scripts/capture-intellij-ui.sh -Plattice.ui.review=true \
  -Plattice.ui.theme=ExperimentalDark -Plattice.ui.output="$PWD/build/ui-review/ExperimentalDark"
```

`uiScreenshotTest` launches the IDE through Starter's `runIdeWithDriver`; a separate manually launched `runIde` process is unnecessary. Runs are sequential because the generated project and sandbox are shared. The Gradle task always executes, while unchanged compilation/build outputs are reused.

## Evidence and output

Full desktop PNGs: `build/ui-review/<theme-id>/*.png`. An index, logs, JUnit results and live-runtime evidence are retained separately for each theme. Rerunning a theme replaces only that theme's capture directory.

The screenshots contain IntelliJ's project tree, editor tabs, status bar, Lattice tool-window chrome and production dialogs. The Driver locates live `DatabaseMainPanel`, `TableDataEditorPanel`, `DatabaseTable`, `SqlQueryConsolePanel`, `ActionLink`, `JBTextArea` and IDE tree components. The test reads the running IDE's actual theme ID and fails if it differs from the requested theme. PNGs are captured with AWT Robot from the running desktop; no preview fixture or recreated UI is involved. Cell edits use the live production JTable editor on the IDE event dispatch thread, including its normal conversion and listener lifecycle; the test does not assign replacement model values.

Platform: IntelliJ IDEA Community 2025.1, build 251.23774.435; IDE JBR 21.0.6+9-b895.109-jcef; test-worker JDK 21.0.12.1. Display: Xvfb, 1920×1080×24, 96 DPI, scale 1.0, xfwm4 without compositing. Normal IDE size is 1400×1000; narrow captures resize it to 1000×800 while retaining the Project and Lattice tool windows, leaving about 190 pixels for the editor. Wide captures use a 1900×1000 IDE window. The PNG dimensions remain 1920×1080 in every case.

## Coverage

| Area | Real UI states driven |
|---|---|
| First use | Welcome editor, empty explorer, Add connection menu |
| Connections | MySQL Standard/JDBC URL; HSQLDB server/file/memory forms; Edit connection; live HSQLDB test success; genuine loopback MySQL connection failure and inline feedback |
| Drivers | Project POM default; BUNDLED/DOWNLOADED/DISCOVERED options for both products; available/download lists; Local JAR; progress/locked actions; completed download |
| Explorer | Connected schema/table tree; connection/table context menus; disconnected state |
| Schema | Create schema with empty/invalid name entered; Create table and SQL preview; all five Alter Table cards; drop column/table/schema confirmations, cancelled |
| Table | Empty/persisted grid; required/nullable/auto defaults; valid insertion; unchanged/invalid/restored/valid integer edit; required NULL blocked in menu; literal NULL string; disabled context actions; new-row database default; Revert; empty filter/error/reset; export/options menus; CREATE TABLE viewer; delete/truncate confirmations, cancelled; narrow editor |
| SQL | Initial query; SELECT results; empty results; UPDATE affected rows; genuinely running query and Stop; cancellation; query error/Messages/Results tabs; history/template menus; narrow console |
| Removal | Remove saved connection confirmation, cancelled |

Bundled theme IDs: `ExperimentalDark` (Dark), `ExperimentalLight` (Light), `ExperimentalLightWithLightHeader` (Light with Light Header), `JetBrainsHighContrastTheme` (High Contrast), `Darcula`, `IntelliJ`, `JetBrainsLightTheme` (IntelliJ Light). IDEA migrates the last ID at startup under its new UI; the review explicitly selects the installed IntelliJ Light theme through the live LafManager API, then verifies its ID.

This is broad flow/state coverage, not an exhaustive combination of every database value, server release, window width, font size and OS. See the remaining coverage section below.

### Theme observations

| Theme | Visual observations |
|---|---|
| Dark | Dark fields, blue focus/selection, green/red pending actions. Layout defects are visible without font or scaling distortion. Revert and muted NULL text need contrast attention. |
| Light | White inputs and popups on a pale IDE background. Required-cell errors and pending changes remain distinguishable; unselected NULL text is particularly faint. |
| Light with Light Header | Light header replaces the dark top strip; plugin form/grid geometry stays consistent with Light. The same wrapping and dialog issues persist. |
| High Contrast | Black surfaces, strong field borders, cyan focus/native primary actions and orange information text. Custom Commit/Revert fills still use the ordinary dark palette. Source/version/action left edges shift together by one pixel, preserving alignment. An explicit live JFrame repaint before settled captures restores IDE chrome that initially remained unpainted after dialogs closed; the initial image is preserved in the investigation directory. |
| Darcula | Gray forms and subdued blue native actions; control sizes stay consistent. Muted NULL text has the weakest measured contrast among the sampled dark backgrounds. |
| IntelliJ | Gray surrounding panels and white editor/table surfaces. Menus and fields remain readable; the same fixed column widths, Alter Table alignment and narrow layouts need attention. |
| IntelliJ Light | Similar classic light palette; the actual requested theme is verified after explicit live selection. Pending/invalid row states and menus render consistently, with the same layout issues. |

## Findings

Priorities: P1 blocks or misleads an operation; P2 materially affects readability or consistency; P3 is polish. Screenshot links below point to generated artifacts and become available after running the script. They are observations and proposed follow-up work, not production fixes made by this review.

The subsequent [P1 implementation and real-IDE validation](UI_P1_FIXES.md) addresses the three P1 findings below. The approved [P2 implementation and validation](UI_P2_FIXES.md) addresses readability and consistency, including the remaining HSQLDB interruption limit. This review preserves the original evidence; P3 remains a separate approval phase.

| Priority | Finding | Evidence / suggested improvement |
|---|---|---|
| P1 | Narrow table editor clips whole toolbar/filter regions and footer text. | [Table narrow](../build/ui-review/ExperimentalDark/table-narrow.png): at a 1000-pixel IDE width with both side windows open, Export is partly behind the grid header, WHERE/ORDER BY disappear, and the Rows selector/status are clipped. Make heading height follow the wrapped layout, use overflow for action groups, and make the footer wrap or compact. [Wide comparison](../build/ui-review/ExperimentalDark/table-wide.png). |
| P1 | Narrow SQL console loses History/Template controls and status text. | [Console narrow](../build/ui-review/ExperimentalDark/sql-console-narrow.png): the Database selector and shortcut are clipped, History/Template are absent, and Max rows occupies the footer while the error status disappears. Keep action/recall strips visible when they wrap; give status its own responsive row. |
| P1 | Download becomes enabled for an already downloaded MySQL version after changing connection mode/testing failure. | [Completed download](../build/ui-review/ExperimentalDark/mysql-driver-downloaded.png) versus [failed test inline](../build/ui-review/ExperimentalDark/mysql-connection-failure-inline.png). Reproduce: download 5.1.49, switch to JDBC URL, test a refused loopback connection, dismiss the error. Live `isEnabled()` changes from false to true without changing version. `ConnectionDialog.doTestConnection()` unconditionally enables it when testing finishes. Recompute availability from the retained driver when unlocking controls. No second download was clicked; source inspection shows `runDriverAction()` guards against downloading an already retained version, so a duplicate transfer is not established. |
| P2 | Alter Table cards move their fields between tabs and vertically center sparse forms. | [Add](../build/ui-review/ExperimentalDark/alter-add.png) versus [Rename column](../build/ui-review/ExperimentalDark/alter-rename-column.png): the input column shifts about 30 pixels; sparse cards have a large gap below the tabs. Share one label/input grid, consistent insets and top alignment. |
| P2 | Alter Table operation buttons stretch to the entire field width. | [Modify](../build/ui-review/ExperimentalDark/alter-modify.png) and [Rename table](../build/ui-review/ExperimentalDark/alter-rename-table.png). Buttons are hundreds of pixels wide while Close and other dialog actions are content-sized. Put operation buttons in a leading-aligned row with common padding. |
| P2 | Create Table header text is truncated at the initial dialog size. | [Create table](../build/ui-review/ExperimentalDark/create-table-dialog.png): Column Name is shortened although there is spare space in small numeric/boolean columns. Allocate widths by content, shorten headers deliberately, and preserve readable names. |
| P2 | Create Table SQL preview is short and initially scrolled past the start of the statement. | Same screenshot: the preview starts with column definitions rather than CREATE TABLE. Reset the caret/scroll after replacing the preview, and allow enough initial height for the small default statement. |
| P2 | Explorer connection row cuts off its connection-state suffix. | [Connected explorer](../build/ui-review/ExperimentalDark/connected-explorer.png): even “Lattice UI demo” consumes most of the 260-pixel tree width once type/state and indentation are included. Use a compact state indicator and tooltip, ellipsize the name deliberately, or give the initial tool window more room. |
| P2 | Invalid-cell feedback does not show the precise type error inline. | [Invalid integer](../build/ui-review/ExperimentalDark/table-edit-invalid-integer.png): the invalid text is clipped in the small ID column; the footer says “Fix before committing”. Production code places the actual reason in the footer tooltip. Show the selected cell's column/type/reason in a readable detail strip or expandable message. |
| P2 | Dark-theme Revert's white text has limited contrast against its red fill. | [Pending edit](../build/ui-review/ExperimentalDark/table-edit-pending.png). Source palette #D94F4F against white measures 4.05:1, below the usual 4.5:1 normal-text target; dark Commit #238636 measures 4.63:1. Light variants measure 5.62:1 and 5.13:1. Darken the red slightly while retaining the white label/icon. This is a palette calculation, not an accessibility certification. |
| P2 | Unselected SQL NULL values are too subdued, especially in the light themes and Darcula. | [Light table context](../build/ui-review/ExperimentalLight/table-context-menu.png) and [Darcula comparison](../build/ui-review/Darcula/table-context-menu.png): NULL is actual data, yet its italic gray is close to the table background. The renderer's nominal foreground against sampled backgrounds measures about 2.78:1 in Light, 2.58:1 in Darcula, and 3.35:1 in Dark. Use a readable theme information foreground and retain a secondary cue such as italics to distinguish SQL NULL. Selected rows use the theme selection foreground and are more readable. |
| P2 | Stop does not promptly interrupt a large HSQLDB aggregate and provides no “cancelling” feedback. | [Preserved running capture](../build/ui-review-investigations/hsqldb-query-running.png), [Stop still pending](../build/ui-review-investigations/hsqldb-stop-pending.png), [timeout result](../build/ui-review-investigations/hsqldb-stop-timeout.xml). A four-way SYSTEM_COLUMNS COUNT aggregate kept Run disabled beyond the 20-second wait after Stop. A bounded real aggregate completes with cancellation feedback after several seconds. A JDBC probe of HSQLDB 2.7.3 showed Statement.cancel() returning while computation continued until it finished. Acknowledge cancellation immediately and investigate driver-specific interruption separately. |
| P3 | User-requested query cancellation is presented as a red query failure. | [Cancelled query](../build/ui-review/ExperimentalDark/sql-console-cancelled.png): Messages says both “Query failed” and “Query cancelled”; footer uses error severity. Represent cancellation as its own neutral outcome while preserving prior results. |
| P3 | Filter Apply wraps alone at normal editor width. | [Pending edit](../build/ui-review/ExperimentalDark/table-edit-pending.png): at roughly 590 pixels of editor space it creates a third header strip. [Wide](../build/ui-review/ExperimentalDark/table-wide.png) fits the filter row. Prefer flexible WHERE/ORDER BY field widths, or a deliberate balanced two-row arrangement. |
| P3 | Copy and capitalization vary between screens. | Create Table uses “Columns Definition”, “SQL DDL Preview”, “Remove Column”, “Move Up/Down”; Alter uses “New Column name” / “New Table name”, while other actions use sentence case. Standardize these labels; “Columns” and “SQL preview” are sufficient. |
| P3 | Singular row counts use plural grammar. | Table and console show “1 rows” / “1 rows affected”. Use “1 row” and preserve plural forms for other counts. |
| P3 | Driver summary repeats product/source metadata and is visually subdued. | [Download mode](../build/ui-review/ExperimentalDark/connection-dialog-download-driver-full.png): “HSQLDB · Download a version · HSQLDB …” competes with the options link. Keep the version visible with a short source badge and a readable information foreground. |

### Areas that look consistent

- Source and editable driver-version controls are both 220×26 pixels, with shared left edges with More versions. The progress bar is thin and matches that width.
- Driver source/version/download controls are disabled while the real MySQL transfer runs. Available/download popups distinguish BUNDLED, DOWNLOADED and DISCOVERED entries. The separate unlocking regression above occurs later.
- Commit/Revert measure 80×28 and 74×28 pixels respectively, with equal 7-pixel horizontal / 4-pixel vertical padding, rounded outlines, white tick/rollback icons and a visible gap. Clean/unchanged/restored rows leave them disabled; valid changes enable Commit; invalid values disable Commit while leaving Revert available.
- New-row required, auto-generated and nullable cells have distinct states. Context menus correctly disable NULL for required fields and database default for existing rows. The new-row default returns to `(Auto)`.
- Native Results/Messages tab titles are visible. After an error, the results tab is labelled Previous results; error details remain in Messages.
- The real explorer/editor integration, panel dividers and tool-window toolbar render cleanly at normal/wide sizes. The observed title bars/borders confirm that the earlier unframed-dialog appearance came from running without a window manager.

### Suggested follow-up order

1. Repair the table and console resize behavior together. Both use `WrapLayout`, which calculates preferred height from its existing width; a later narrower parent allocation can leave wrapped rows without enough height. This is a likely cause from source inspection, not a fix verified in this review. Include the footer's separate width constraint in the fix.
2. Recompute driver download availability after connection testing unlocks the form.
3. Give Alter Table cards a shared top-aligned grid; then adjust Create Table column widths and preview scrolling.
4. Improve invalid-cell details and cancellation feedback, then address contrast, copy and singular counts.

## Runtime warnings and investigation failures

The per-theme logs retain complete stdout/stderr. Relevant excerpts encountered during this review:

```text
_XSERVTransmkdir: Owner of /tmp/.X11-unix should be set to root
Failed to connect to session manager: SESSION_MANAGER environment variable not defined
... failed to transfer ... during a previous attempt. This failure was cached ...
Original error: ... Network is unreachable
java.io.IOException: not a directory: /sys/class/power_supply
Could not add attachment: no test is running
Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
```

The display/session warnings did not prevent rendering. The Maven importer warnings concern cached failures resolving project build plugins, not the actual driver transfer exercised here; genuine driver downloads succeeded. The Allure attachment warning occurs outside an active test and does not change the JUnit result. These warnings remain in the logs.

The deliberately larger HSQLDB aggregate investigation failed with `Timed out waiting for SQL cancellation completes`; its screenshot and failed XML are preserved under `build/ui-review-investigations`. The reusable theme pass uses bounded real database work so that this product limitation does not hang every theme run.

An initial IntelliJ Light automation attempt failed with `Theme not found for themeId: IntelliJ Light`. The live `LafManager.findLaf()` argument is an ID, not a display name; the test now supplies `JetBrainsLightTheme`. The failed XML remains in the investigation directory. This was a capture-infrastructure error before plugin interaction.

## Files changed for reuse

- `build.gradle.kts`: output/theme/review properties and an always-executed screenshot task; production dependencies are unchanged.
- `scripts/capture-intellij-ui.sh`: start and clean up an available window manager alongside Xvfb.
- `scripts/review-intellij-ui.sh`: prepare genuine JDBC fixtures, run requested themes sequentially, retain logs/results and write the screenshot index.
- `src/uiTest/kotlin/com/segfault03/ideadb/ui/IntellijUiScreenshotTest.kt`: extend the existing Starter/Driver flow with live theme selection, more production dialog/menu/query states and resize captures.
- `docs/IDE_UI_REVIEW.md`: this review, evidence, reproduction steps and recommendations.
- `docs/SCRIPTS.md`, `docs/UI_REVIEW.md`, `docs/README.md`: document the entrypoint and link the current review.

## Remaining coverage and platform limits

- MySQL forms, discovery, download and connection failure are exercised. Successful MySQL server browsing/mutations are not covered by this visual pass; live table/query operations use HSQLDB.
- HSQLDB file/server layouts are rendered; their successful connections are not driven in this pass.
- Alter cards and destructive confirmations are inspected; the destructive operations are cancelled. Export menus/DDL viewing are inspected; file export/chooser variants are not all executed.
- Create schema's empty/invalid-name forms are captured and cancelled; their submission validation is not asserted. Create table is actually submitted to build the test table.
- Table coverage uses INT, required VARCHAR and nullable TIMESTAMP. BLOBs, very long text, decimals/booleans/dates, views without keys, concurrent conflicts, multiple-page datasets and draft restoration need additional focused cases.
- Arbitrary third-party themes, enlarged IDE fonts, fractional/HiDPI scaling, keyboard traversal/screen-reader behavior and Windows/macOS rendering are not covered. Seven bundled theme captures cannot establish compatibility with every third-party theme.
- Linux font rasterization and xfwm4 window decorations differ from Windows. The white Xfce modal frame in a dark IDE is desktop chrome, not a Lattice border defect. Without a window manager, dialogs lack these decorations; the reusable script now starts one. Compositing is disabled, so desktop shadows are absent.
- A screenshot is not a contrast or accessibility certification. Semantic action availability is checked for the table and driver flows; broader accessibility needs a dedicated pass.
