# P3 UI fixes

P3 addresses the five polish findings from the [real IntelliJ UI review](IDE_UI_REVIEW.md). It uses the existing production screens and the same JetBrains Starter/Driver workflow as [P1](UI_P1_FIXES.md) and [P2](UI_P2_FIXES.md).

Approved P3 was pushed as `5cfe548` on `feat/retain-drivers`; all three cross-platform commit checks passed in [workflow 37876424328](https://github.com/SegFault03/Lattice/actions/runs/37876424328). Subsequent work is recorded in [the loading feedback report](UI_LOADING_FEEDBACK.md).

## Changes

| Finding | Result |
|---|---|
| Cancellation looks like a query failure | `QueryResult` distinguishes requested cancellation from errors and successful results. The console shows neutral “Query cancelled” feedback, retains previous results and leaves genuine SQL errors red with the error icon. Cancellation uses the execution request flag rather than matching error text. |
| Apply wraps onto a lonely row | WHERE and ORDER BY share flexible space beside Apply at normal widths. At medium widths, WHERE occupies the first row and ORDER BY shares the second row with Apply. Very narrow editors stack the controls. Native field heights and the action's natural width are retained. |
| Schema labels use inconsistent capitalization | Create Table uses “Columns”, “SQL preview”, “Remove column”, “Move up” and “Move down”. Alter Table uses “New column name:” and “New table name:”. |
| Singular row counts use plural grammar | Table and SQL status/messages use “1 row” and “1 row affected”, preserving “0 rows” and plural counts. Saved-row counting also uses singular grammar. |
| Driver summary is repetitive and muted | The summary uses the selected version plus a short source label: BUNDLED, DOWNLOADED, DISCOVERED, DOWNLOAD or LOCAL JAR. Normal theme text improves readability. The full database product/version/source remains in the tooltip and accessible description. |

The responsive filter panel arranges the existing production controls; it does not duplicate their actions or construct a test UI. Test dependencies remain isolated from the production plugin.

## Repeat

```sh
LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p3" ./scripts/review-intellij-ui.sh
```

Append `ExperimentalDark ExperimentalLight` for a shorter pass. If display `:99` is occupied, prefix `XVFB_DISPLAY=:100` using a free display. Each run builds/installs the actual plugin and launches IDEA through `./gradlew uiScreenshotTest`; no separate IDE launch is required.

The [script reference](SCRIPTS.md) documents prerequisites and all seven theme IDs. Screenshots, logs, JUnit XML and live component evidence stay under ignored `build/` output.

## Validation

All seven real-IDE scenarios passed: **7 JUnit tests, zero failures/errors/skips**, **91 full-desktop PNGs per theme**, **637 total**. Every PNG was decoded and verified to be 1920×1080. The unit suite passed **56 tests, zero failures/errors**; the Python tooling suite passed **56 tests, one skipped**. `git diff --check` passes. These results validate the plugin flows and component checks below, subject to the surrounding-IDE painting limit described later.

| Theme ID | Scenario / PNGs | Live driver-summary contrast | Representative captures personally inspected |
|---|---|---:|---|
| ExperimentalDark | PASS / 91 | 10.55:1 | Yes |
| ExperimentalLight | PASS / 91 | 19.76:1 | Yes |
| ExperimentalLightWithLightHeader | PASS / 91 | 19.76:1 | Yes |
| JetBrainsHighContrastTheme | PASS / 91 | 21.00:1 | Yes |
| Darcula | PASS / 91 | 5.53:1 | Yes |
| IntelliJ | PASS / 91 | 18.76:1 | Yes |
| JetBrainsLightTheme | PASS / 91 | 18.76:1 | Yes |

The [screenshot index](../build/ui-review-p3/index.md) links all final captures. [Aggregate validation](../build/ui-review-p3/validation-summary.json) records decoded dimensions, JUnit results, live field widths/row modes, driver-summary palettes and cancellation/error checks. Each theme directory also contains `runtime-evidence.txt` and its saved JUnit XML.

I directly opened representative full-desktop images in every theme, rather than claiming to inspect all 637 individually. Concrete observations:

- [Normal-width table](../build/ui-review-p3/ExperimentalDark/table-normal-after-resize.png): WHERE, ORDER BY and Apply occupy one line with visible field borders and spacing. Apply no longer adds a separate header strip.
- [Medium-width table](../build/ui-review-p3/ExperimentalLight/table-medium-filters.png): both field left edges align; WHERE fills the first row and Apply sits beside ORDER BY on the second. The footer reads “1 row”. Narrow captures retain visible stacked fields and the native single-row action overflow.
- [Cancelled first query](../build/ui-review-p3/ExperimentalDark/sql-console-cancelled-empty.png) and [cancelled with previous results](../build/ui-review-p3/ExperimentalDark/sql-console-cancelled.png): normal text, no error glyph, and clear elapsed/preservation feedback. [Actual SQL error](../build/ui-review-p3/IntelliJ/sql-console-error.png) keeps its red footer/error glyph. [One-row update](../build/ui-review-p3/IntelliJ/sql-console-update.png) shows “Affected 1 row” and “1 row affected”.
- [Create Table](../build/ui-review-p3/ExperimentalDark/create-table-dialog.png) and [Rename Table](../build/ui-review-p3/ExperimentalLightWithLightHeader/alter-rename-table.png): the new labels are visible, actions remain naturally sized and the P2 form/header/preview alignment is retained.
- [Discovered summary](../build/ui-review-p3/ExperimentalDark/mysql-standard.png), [downloaded summary](../build/ui-review-p3/ExperimentalLight/mysql-driver-summary-retained.png) and [local-JAR summary](../build/ui-review-p3/Darcula/driver-local-jar-selected.png): versions and short source labels are readable and fit beside Driver options. The long local path stays in its native field, rather than expanding the summary.

Final results were collected from the Dark run, completed themes in the first remaining-theme batch, and focused reruns. Successful focused commands were:

```sh
XVFB_DISPLAY=:100 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p3" \
  ./scripts/review-intellij-ui.sh ExperimentalDark
XVFB_DISPLAY=:101 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p3" \
  ./scripts/review-intellij-ui.sh JetBrainsHighContrastTheme
XVFB_DISPLAY=:101 LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p3" \
  ./scripts/review-intellij-ui.sh IntelliJ JetBrainsLightTheme
```

The first remaining-theme batch passed Light, Light with Light Header, High Contrast, Darcula and IntelliJ before interruption in IntelliJ Light. High Contrast/IntelliJ were repeated while investigating background painting; earlier versions stay outside the final count under the investigations directory.

The launched IDE is IntelliJ IDEA Community **2025.1**, build **251.23774.435**, using JBR **21.0.6+9-b895.109-jcef**. The test worker uses Eclipse Adoptium **21.0.12.1**. Xvfb supplies **1920×1080×24**, 96 DPI, scale **1.0**, with xfwm4 decorations and compositing disabled. Every PNG is an AWT Robot capture of the actual desktop.

The additional checks use the live `TableDataEditorPanel`, `DatabaseTable`, `SqlQueryConsolePanel`, `JLabel`, `JBTextArea`, `JTabbedPane` and scroll-pane viewport. They verify:

- One filter row at 1400×1000 and 1900×1000 IDE sizes, two rows at 1120×900, three at 1000×800, and a correct layout after restoring the original size. Field widths stay at least 80 pixels and Apply aligns with its intended row. Applying/resetting a narrow filter exercises the production SQL action.
- Cancellation before any successful query leaves the real result model empty. Cancellation with prior results preserves the previous row. Both outcomes have normal text color and no error icon; a genuine missing-table error retains its error color/icon. A subsequent SELECT succeeds.
- Zero, singular and plural SQL result counts, zero/singular affected-row counts, and singular table status text.
- Compact version/source text for bundled, downloaded, discovered, not-yet-downloaded and local-JAR selections. Live summaries fit their labels and pass a 4.5:1 palette contrast check.
- The existing P1/P2 coverage, including native hover overflow, icon-only popup actions, cell/new-row validation, driver-download progress/locking, schema forms and NULL/Revert palette checks.

Unit validation uses the actual in-memory HSQLDB driver. It checks a pre-requested cancellation, a later successful query on the same connection, and an actual SQL error whose missing quoted identifier is `Query cancelled`. Error wording alone must not classify that failure as user cancellation.

```sh
JAVA_HOME=/workspace/.jdk21 PATH=/workspace/.jdk21/bin:$PATH \
  ./gradlew test compileUiTestKotlin --offline
python3 -m unittest discover -s scripts/tests
```

The Java path is specific to this workspace. Use an installed JDK 21 elsewhere.

Two initial Dark attempts exposed selectors in the new test code, rather than production failures. The first selector matched both the WHERE label and its input; it now names the input's actual class. An empty, zero-column result table is omitted from Driver's visible hierarchy, so the cold-cancellation check reads the real Swing table through its scroll-pane viewport. Failed-run PNGs/XML are retained under `build/ui-review-p3-investigations/`, outside the final capture count.

An execution-session interruption stopped the first IntelliJ Light run after 63 captures; its partial output is retained separately. The completed themes were preserved and the unfinished theme was resumed on free display `:101`, rather than reusing stale `:99`/`:100` locks.

## Limits

HSQLDB's delayed interruption of large aggregates is unchanged: the UI acknowledges Stop immediately and displays the neutral final outcome after the driver worker finishes. This phase does not claim that `Statement.cancel()` stops every driver promptly. See the [P2 JDBC probe results](UI_P2_FIXES.md#hsqldb-interruption-investigation).

Linux font rasterization, window decorations and scale-1 screenshots do not establish Windows font metrics or HiDPI equivalence. Live color contrast checks cover component palettes, not a complete accessibility audit.

Some High Contrast and legacy IntelliJ captures have blank areas in the surrounding Project window/IDE chrome while the actual plugin console and Lattice tool window remain painted. A synchronous `JRootPane.paintImmediately()` on the live IDE EDT was investigated and is requested before settled captures, but it did not eliminate every blank background area. This remains a virtual-desktop capture limitation with an unresolved root cause. Passing component/layout/state checks do not prove that every surrounding IDE pixel was painted correctly. The affected screenshots still provide useful evidence for the production plugin UI; use an unaffected capture to assess the complete IDE chrome.

Successful database mutations in this UI matrix use HSQLDB. MySQL coverage exercises real Maven discovery/download, progress and refused-connection feedback, rather than a successful live MySQL server mutation.

Remaining output includes the same non-blocking harness warnings as P2:

```text
WARNING: the GTK 2 library is deprecated and its support will be removed in a future release
Original error: Could not transfer artifact ... Network is unreachable
SEVERE: Could not add attachment: no test is running
Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
```

The network message is a cached negative Maven importer result resolving build plugins. The JDBC fixture downloads and production driver transfer succeed independently. The Allure warning concerns teardown attachments; PNGs and JUnit results are retained separately.

## Files

- Production: `QueryResult`, `DataService`, `SqlQueryConsolePanel`, `TableDataEditorPanel`, the small internal `TableFilterPanel`, and the three existing connection/Create/Alter dialogs.
- Tests: `QueryCancellationResultTest`, the cancellation assertions in `FunctionalRegressionTest`, and the existing `IntellijUiScreenshotTest` scenario.
- Documentation: this report, the review/script/documentation indexes, and the P2 push/CI record.
- No new launcher or testing framework. The existing review script remains the reusable entrypoint; generated captures remain ignored.
