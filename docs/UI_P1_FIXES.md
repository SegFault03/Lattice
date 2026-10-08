# P1 UI fixes

Result: **SUCCESS**. P1 implementation and validation across the seven bundled themes are complete. P2 and P3 require approval after the preceding phase.

## Changes

- Table and SQL action rows now use IntelliJ's `ActionToolbar` with `ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY`. A native arrow appears when the row runs out of space; hovering opens IntelliJ's expanded single-row toolbar.
- Every overflow button with an icon and label shows only its icon in that popup. Commit/Revert keep their green/red fills, rounded outlines and white icons. Main-toolbar labels stay visible, and popup buttons retain full tooltips and accessible names. Mini buttons measure 28×28 at scale 1.
- Popup controls run the existing production action listeners using the visible button as the invoker. Picker views share the production selection model. Closing views detaches their listeners/model.
- Headers measure their wrapped filter/recall rows using the current editor width before allocating height. Fields can shrink, and WHERE/ORDER BY share one label width. Footer status has a wrapping row above the compact controls.
- Finishing a connection test recomputes download availability. Download stays disabled for retained/discovered versions; More versions unlocks. Availability also remains disabled while testing is in progress.

## Repeat

```sh
LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p1" ./scripts/review-intellij-ui.sh
```

Supply theme IDs for a shorter pass, such as `ExperimentalDark ExperimentalLight`. The output override preserves the original review at `build/ui-review/`. The script saves full-desktop PNGs, theme logs, JUnit results, runtime evidence and an index.

Unit checks:

```sh
JAVA_HOME=/workspace/.jdk21 PATH=/workspace/.jdk21/bin:$PATH ./gradlew test --offline
```

The explicit Java path is this cloud environment's JDK 21; use the installed JDK 21 elsewhere.

## Real IDE evidence

- [Table: narrow editor](../build/ui-review-p1/ExperimentalDark/table-narrow.png)
- [Table: hover-expanded toolbar with pending changes](../build/ui-review-p1/ExperimentalDark/table-narrow-toolbar-expanded-pending.png)
- [Table: filter applied at narrow width](../build/ui-review-p1/ExperimentalDark/table-narrow-filter.png)
- [SQL: narrow editor](../build/ui-review-p1/ExperimentalDark/sql-console-narrow.png)
- [SQL: hover-expanded toolbar](../build/ui-review-p1/ExperimentalDark/sql-console-narrow-toolbar-expanded.png)
- [Retained MySQL driver after a failed connection test](../build/ui-review-p1/ExperimentalDark/mysql-connection-failure-inline.png)
- [Capture index](../build/ui-review-p1/index.md)

JetBrains Starter/Driver launches the production plugin in IDEA Community 2025.1 (251.23774.435), using its JBR 21.0.6. Xvfb supplies 1920×1080×24 at scale 1; xfwm4 provides window decorations. Captures use AWT Robot on the real desktop.

The test locates the live native `ActionToolbarImpl` and `ActionToolbarImpl$PopupToolbar`, checks that visible actions occupy one row, and checks every labelled action's icon-only popup view and tooltip. It clicks the popup's actual Revert action and verifies the database grid restores the persisted value. Narrow-width Apply filtering and the History/Template popups also run through the production controls. Component visible rectangles and preferred heights are checked after narrow, wide and restored layouts.

## Validation status

All seven real-IDE runs passed (one scenario per theme, zero failures/errors). Each saved 79 full-desktop PNGs: **553 images total**, decoded and checked to be 1920×1080. The unit suite has **51 tests with zero failures/errors**. Shell syntax and `git diff --check` also pass.

| Theme ID | UI test | PNGs | Table and SQL hover captures personally inspected |
|---|---|---|---|
| ExperimentalDark | PASS | 79 | Yes |
| ExperimentalLight | PASS | 79 | Yes |
| ExperimentalLightWithLightHeader | PASS | 79 | Yes |
| JetBrainsHighContrastTheme | PASS | 79 | Yes |
| Darcula | PASS | 79 | Yes |
| IntelliJ | PASS | 79 | Yes |
| JetBrainsLightTheme | PASS | 79 | Yes |

The full command above passed the first four themes. After fixing the redundant Escape described below, the remaining themes passed with:

```sh
LATTICE_UI_REVIEW_OUTPUT="$PWD/build/ui-review-p1" ./scripts/review-intellij-ui.sh Darcula IntelliJ JetBrainsLightTheme
```

Each script call launches the real IDE through `./gradlew uiScreenshotTest -Plattice.ui.review=true -Plattice.ui.theme=<theme-id> -Plattice.ui.output=<result-dir>`. Theme-specific stdout/stderr is saved in `build/ui-review-p1/<theme-id>.log`; JUnit results and runtime evidence accompany each theme's PNGs.

## Visual inspection

The full IDE captures show the Project and Lattice tool windows, editor tabs, production database grid and native expanded toolbar. I opened both table and SQL hover captures in every theme, plus representative collapsed/normal-width views and the retained-driver failure view, directly to inspect the rendered controls.

- The expanded table toolbar stays on one row. Commit, Revert and Export are compact icon-only buttons; Commit/Revert retain their colored rounded fills. The normal-width toolbar retains its text labels and equal spacing around Commit/Revert.
- The expanded SQL toolbar shows icon-only Run, Stop and Clear, followed by the Database picker. Tooltips and accessible names retain the actions' labels.
- WHERE and ORDER BY inputs share a left edge in the narrow view. Their fields shrink while their labels and Apply remain visible.
- History and Template remain visible at the narrow width. Their selected text is deliberately ellipsized; opening the dropdown displays complete entries.
- Status messages wrap above pagination/Max rows instead of losing text beneath those controls. The narrow table and SQL editor retain horizontal scrolling for data/query content.

## Remaining environment warnings

The theme logs contain these excerpts:

```text
Original error: Could not transfer artifact ... Network is unreachable
WARNING: the GTK 2 library is deprecated and its support will be removed in a future release
SEVERE: Could not add attachment: no test is running
Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
```

The network warning comes from cached Maven importer failures resolving Maven build plugins. The driver fixture downloads and live plugin driver-download flow completed; the UI checks used those real jars. The attachment warning is from Allure's teardown reporting. PNGs and JUnit results are saved independently. These warnings remain outside the P1 production fixes.

The first push's cross-platform workflow failed before building because the documentation-link check required generated `build/` reports/screenshots to exist in a fresh checkout. The check now exempts resolved paths inside the ignored build-output directory while continuing to reject missing source-controlled documents, including paths that traverse back out of `build/`. A regression check covers the fresh-checkout case.

After that correction, Windows/macOS passed and Ubuntu exposed a second failure in the legacy standalone preview: `ApplicationManager.getApplication()` was null when constructing IntelliJ's native `ActionToolbar`. Ubuntu CI now installs Xvfb/xfwm4 and runs the existing real-IDE review in Dark, retaining `build/ui-review/` as an artifact. This gives the native toolbar its real application/runtime rather than adding a standalone toolbar substitute. The old headless preview helper is no longer the CI rendering gate; use the real-IDE workflow for the table/console screens.

An initial Darcula run failed with `Timeout(15s): ... New connection ... none`. The error screenshot showed that the dialog had closed: an extra Escape after Enter accepted a driver version could dismiss the dialog, depending on when its dropdown closed. Removing that redundant keystroke lets the following live component lookup wait for the selection. Darcula and the remaining themes passed after the fix. The failure log, JUnit result and screenshot are preserved under `build/ui-review-p1-investigations/darcula-extra-escape/`.

The tested IDE sizes are 1000×800, 1400×1000 and 1900×1000; the narrow editor retains both side tool windows and has about 190 pixels available. Other OS font rendering, enlarged fonts, HiDPI scales and extremely small editor dimensions require separate coverage. The original review's P2/P3 findings remain the next approval phases.
