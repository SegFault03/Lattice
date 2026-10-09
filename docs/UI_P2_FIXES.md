# P2 UI fixes

P2 addresses the nine readability and consistency findings from the [real IntelliJ UI review](IDE_UI_REVIEW.md). Prompt interruption of large HSQLDB read aggregates remains a driver limitation, described below.

After approval, P2 was committed and pushed to `feat/retain-drivers` as `da7f6ab`. All three cross-platform commit checks passed in [workflow run 37829130195](https://github.com/SegFault03/Lattice/actions/runs/37829130195). The validation below records the original P2 review; [P3](UI_P3_FIXES.md) is documented separately.

## Changes

| Original finding | Implemented behavior |
|---|---|
| Alter Table fields move between cards | All five cards share the existing form layout, one rendered label-column width, common insets and top-aligned rows. |
| Alter Table operation buttons stretch | Actions keep their natural preferred width and align with the input column. |
| Create Table headers truncate | Initial dialog width is increased; column minimum widths follow the actual native header renderer. The dialog is resizable. |
| SQL preview starts below `CREATE TABLE` | Preview updates reset the caret to the start, and eight visible rows accommodate the default statement. |
| Explorer connection-state suffix clips | A leading database/state icon accompanies the connection name; the full name, database product, state and expansion hint appear in the tooltip and accessible description. |
| Cell type errors appear only in a tooltip | A wrapping detail row shows the selected invalid cell's row, column, SQL type and precise reason. Without an invalid selection, it shows the first error. Very long details are capped inline and retained in the tooltip. |
| Dark Revert text contrast is low | Dark Revert uses `#C43D3D` with white text/icons; live palette checks enforce at least 4.5:1. |
| Unselected SQL NULL is too faint | Table and console render NULL with the theme's normal table foreground, retaining italics. |
| Stop is slow on HSQLDB and gives no cancelling feedback | Stop records the request synchronously, shows “Cancelling query… · Waiting for the driver”, disables repeated Stop and keeps Run disabled until the actual worker finishes. JDBC cancellation runs off the event dispatch thread; the driver's delayed aggregate interruption remains as documented below. |

The existing production dialogs, renderers and action listeners are exercised. There is no parallel UI implementation or mock. Test dependencies remain outside the production plugin.

## Repeat

```sh
LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p2" ./scripts/review-intellij-ui.sh
```

For a shorter pass, append `ExperimentalDark ExperimentalLight`. The output override preserves earlier review artifacts. Each theme launches through the existing Gradle task:

```sh
LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" \
  ./scripts/capture-intellij-ui.sh -Plattice.ui.review=true \
  -Plattice.ui.theme=ExperimentalDark -Plattice.ui.output="$PWD/build/ui-review-p2/ExperimentalDark"
```

The wrapper executes `./gradlew uiScreenshotTest` with those properties. JetBrains Starter's `runIdeWithDriver` builds/installs the plugin and starts the actual IDE; no separate manually launched IDE is needed. Requirements and theme IDs are listed in the [script reference](SCRIPTS.md).

Unit checks:

```sh
JAVA_HOME=/workspace/.jdk21 PATH=/workspace/.jdk21/bin:$PATH ./gradlew test --offline
```

That Java path is specific to this cloud workspace; use an installed JDK 21 elsewhere.

## Live IDE evidence

The test IDE is IntelliJ IDEA Community **2025.1**, build **251.23774.435**, using JBR **21.0.6+9-b895.109-jcef**. The test worker uses JDK 21. Xvfb provides a **1920×1080×24**, 96-DPI display at scale **1.0**, with xfwm4 decorations and compositing disabled. Every PNG is an AWT Robot capture of the real desktop containing IDEA, its tool windows and the production plugin screen.

The Driver locates actual `JBTable`, `JBTextArea`, `TableDataEditorPanel`, `DatabaseTable` and `SqlQueryConsolePanel` instances. It reads live Swing bounds, native header renderer sizes, caret/visible-rectangle positions, selection-dependent messages and actual component colors. The existing native hover-toolbar checks remain part of every theme run.

- [Create Table: readable headers and complete preview](../build/ui-review-p2/ExperimentalDark/create-table-dialog.png)
- [Alter Table: Add column](../build/ui-review-p2/ExperimentalDark/alter-add.png)
- [Alter Table: Rename table](../build/ui-review-p2/ExperimentalDark/alter-rename-table.png)
- [Selected cell's precise error](../build/ui-review-p2/ExperimentalDark/table-selected-cell-error.png)
- [Narrow editor with wrapped type error](../build/ui-review-p2/ExperimentalDark/table-narrow-invalid-cell.png)
- [Unselected SQL NULL](../build/ui-review-p2/ExperimentalDark/table-null-unselected.png)
- [Immediate cancellation feedback](../build/ui-review-p2/ExperimentalDark/sql-console-cancelling.png)
- [Screenshot index](../build/ui-review-p2/index.md)

## Validation

All seven real-IDE scenarios passed: one JUnit scenario per theme, **zero failures/errors**, **84 full-desktop PNGs each**, **588 total**. Every image was decoded and checked to be 1920×1080. The final unit suite has **54 tests, zero failures/errors**. The Python tooling suite passes **56 tests, one skipped**. Shell syntax and `git diff --check` pass.

| Theme ID | UI test / PNGs | Live Revert contrast | Live NULL contrast (table and SQL) | Cancelling feedback | Representative captures personally inspected |
|---|---|---:|---:|---:|---|
| ExperimentalDark | PASS / 84 | 5.14:1 | 10.55:1 | 636 ms | Yes |
| ExperimentalLight | PASS / 84 | 5.62:1 | 19.76:1 | 603 ms | Yes |
| ExperimentalLightWithLightHeader | PASS / 84 | 5.62:1 | 19.76:1 | 589 ms | Yes |
| JetBrainsHighContrastTheme | PASS / 84 | 5.14:1 | 21.00:1 | 648 ms | Yes |
| Darcula | PASS / 84 | 5.14:1 | 5.53:1 | 573 ms | Yes |
| IntelliJ | PASS / 84 | 5.62:1 | 21.00:1 | 601 ms | Yes |
| JetBrainsLightTheme | PASS / 84 | 5.62:1 | 21.00:1 | 606 ms | Yes |

Feedback timing includes the Driver click and live hierarchy lookup. Every run checks that Run and repeated Stop remain disabled while cancelling, previous results survive, and a subsequent SELECT succeeds. Each Alter card's first field has the same live screen coordinates within its theme; its action aligns with that field and measures its natural preferred width.

Successful results were collected across the initial batch and resumed runs. The commands used were the full review command above, followed by:

```sh
XVFB_DISPLAY=:100 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p2" \
  ./scripts/review-intellij-ui.sh ExperimentalLight ExperimentalLightWithLightHeader JetBrainsHighContrastTheme Darcula IntelliJ JetBrainsLightTheme
XVFB_DISPLAY=:100 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p2" \
  ./scripts/review-intellij-ui.sh JetBrainsHighContrastTheme Darcula IntelliJ JetBrainsLightTheme
XVFB_DISPLAY=:100 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p2" \
  ./scripts/review-intellij-ui.sh IntelliJ JetBrainsLightTheme
```

The saved final results/index are under `build/ui-review-p2/`; aggregate decoded-image/test/palette evidence is in `validation-summary.json`. The last command completed successfully. Earlier attempts stopped for the investigated issues below; their partial captures are excluded from the 588-image count. Darcula's scenario finished successfully but its wrapper stopped before copying JUnit; the passing Darcula XML was verified against its logged live theme and preserved before the next run.

I directly opened representative full-IDE PNGs in every theme, rather than claiming every image was personally reviewed. Concrete observations:

- Create Table shows all seven complete headers and the default SQL from `CREATE TABLE` through its closing line. No initial preview scrolling or header clipping appears in the inspected captures.
- Alter cards begin just below their tabs; sparse cards retain free space below the form. Inputs share their left edge across cards, and operation buttons remain compact instead of stretching across the field width.
- The explorer displays the leading database/checkmark state indicator and the full test connection name in the normal tool-window width. Product/state text is retained in the live tooltip/accessibility description.
- Selected-cell details show the SQL type and exact reason. At narrow width, the integer reason wraps completely above the Rows/Page controls without clipping.
- Unselected NULL is clearly readable in the inspected Dark, light-header, Darcula and IntelliJ Light data captures, retaining an italic cue. The remaining themes also pass the live renderer palette checks.
- Enabled Revert retains its rounded red fill and white label/icon. Cancelling captures show disabled Run/Stop, preserved previous results and visible feedback at the bottom of the SQL editor.

The prior P1 push also needed CI corrections: generated-report links are valid without local build output; Ubuntu's rendering gate now uses the real IDE instead of the legacy standalone preview; and x11-utils supplies its display-readiness probe. **All three cross-platform commit checks passed for pushed commit `545d1ae`** in [workflow run 37826635795](https://github.com/SegFault03/Lattice/actions/runs/37826635795). Ubuntu's real-IDE Dark step completed with `BUILD SUCCESSFUL in 4m 26s` and logged the live `DatabaseMainPanel`. Those CI results cover the pushed P1/CI changes; the approved P2 push and its separate CI results are recorded above.

Two test-only issues were investigated and corrected during validation. An initial Dark run queried a result grid while its Messages tab was selected; selecting the native Previous results tab before inspecting retained rows fixed the visibility lookup. In High Contrast, the Driver tree double-click expanded the table's children but did not open an editor; the flow now clicks the existing production “Open data editor” context-menu item. Logs, failure PNGs and partial captures are preserved under `build/ui-review-p2-investigations/`. An execution-service interruption also stopped the first Light attempt; its partial evidence was preserved and Light passed on restart using `XVFB_DISPLAY=:100`.

## HSQLDB interruption investigation

Run the reusable, isolated real-JDBC diagnostic:

```sh
./scripts/probe-hsqldb-cancellation.sh
./scripts/probe-hsqldb-cancellation.sh build/ui-test-maven/repository/org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar
```

The default is the bundled HSQLDB 2.7.4 jar. This uses a disposable in-memory database, a bounded read-only aggregate and Java source-file execution of the test-only probe. A 45-second process timeout bounds drivers that ignore cancellation/query timeout; it terminates only that diagnostic JVM.

Both actual runs returned the full count after `Statement.cancel()`, rather than interrupting the computation:

| HSQLDB driver | Without cancellation | `cancel()` duration | Worker duration with cancellation | Connection usable afterwards |
|---|---:|---:|---:|---|
| 2.7.3 | 4419 ms | 0 ms | 3755 ms | Yes |
| 2.7.4 | 4636 ms | 0 ms | 3532 ms | Yes |

Representative stdout:

```text
Driver: HSQL Database Engine Driver 2.7.4
Statement.cancel returned after 0 ms; worker done=false
With cancellation: returned COUNT=103836100; worker finished after 3532 ms
Connection usable afterwards: true
```

Source inspection of HSQLDB's JDBC/session cancellation confirms that it marks transaction/statement cancellation, while these read aggregate loops continue. Closing the shared connection can also wait behind execution and would discard the session; unsafe forced thread termination or database shutdown is not used. **Immediate UI acknowledgement is fixed; prompt termination of this HSQLDB query is not established.** Run deliberately stays blocked until the real execution ends. The real-IDE test also verifies that previous results survive and a subsequent SELECT succeeds.

Finished user cancellation still has the original red “Query failed” presentation. Its neutral outcome is the separate P3 finding and has not been changed in P2.

## Remaining warnings and platform limits

The logs contain existing environment/tooling warnings:

```text
Original error: Could not transfer artifact ... Network is unreachable
WARNING: the GTK 2 library is deprecated and its support will be removed in a future release
SEVERE: Could not add attachment: no test is running
Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
```

The network warning is a cached Maven importer failure resolving Maven build plugins. Genuine JDBC fixture downloads and the plugin's driver-download flow are verified independently. The Allure attachment warning is teardown reporting; PNGs and JUnit results are saved separately.

Linux fonts/window decorations differ from local Windows rendering. These checks use scale 1 and the seven bundled themes; Windows/macOS, fractional HiDPI, enlarged fonts and third-party themes need separate validation. Palette ratios are useful readability checks, not an accessibility certification. Successful live database operations use HSQLDB; MySQL coverage includes real driver discovery/download and connection-failure handling, not successful server mutation flows.

## Files

- Production: `ConnectionFormPanel`, `AlterTableDialog`, `CreateTableDialog`, `DatabaseTreeCellRenderer`, `DatabaseUi`, `TableDataEditorPanel`, `SqlQueryConsolePanel`, `QueryExecution`, `DataService`.
- Tests: expanded `IntellijUiScreenshotTest`; new `QueryExecutionTest`, `DatabaseTreeCellRendererTest` and `HsqlCancellationProbe`.
- Reuse/documentation: `scripts/probe-hsqldb-cancellation.sh`, this report and links from `IDE_UI_REVIEW.md`/`SCRIPTS.md`.

Generated screenshots/logs remain ignored under `build/`.
