# Hand-off

Delete before a release (see `CLAUDE.md`).

## Goal
Take Lattice from 0.2.0-alpha to 1.0 as the data tools layer for IntelliJ IDEA without the Ultimate subscription. Plan, task list and milestones: `ROADMAP.md`. UI rules, chosen directions, motion and bans: `DESIGN.md`.

## Standing decisions (owner, 2026-11-26)
- Positioning: data tools layer; reuse the IDE's free data sources where available; own connections for 2025.1–2025.2 and when the Database plugin is off.
- 1.0 engines: MySQL, MariaDB, PostgreSQL, SQLite, H2, HSQLDB (MariaDB and SQLite not built yet; they need providers and icons).
- Branch `claude/workspace`; the owner approved pushing it at every checkpoint without asking. No PRs unless asked.
- UI work: concepts first, owner picks, then build; follow `DESIGN.md` and the `anthropic-skills:design-primer` skill.
- Explorer status shows only what Lattice already knows (open session, last open failed); never contact a server just to show status.
- Environment tags are a fixed set: none, local, development, staging, production.
- Motion confirmed as proposed (quiet, ease-out-quart, one authored moment per surface; details in `DESIGN.md` → Motion scheme).
- R3 order: explorer first, then the rest.

## Done
- Review, competitor research and `ROADMAP.md`; milestone 0.2.x T0.1–T0.7, T0.9–T0.12 (T0.8 waits for the owner).
- R7 / T5.1 welcome as home, accepted and closed with the design-primer `accept` step (concept code deleted; artifact https://claude.ai/artifact/SzpE9pCdFyDxAyewLuDbnK republished with a "Chosen" banner; `DESIGN.md` updated):
  - No welcome tab or `showWelcomeScreen` setting; the explorer's help button opens What's New (`DatabaseFileTypes.RELEASE_NOTES`).
  - First run: `EmptyConnectionPanel`, one `ActionLink` per `DatabaseType` opening the connection dialog (`DatabaseMainPanel.addConnection`).
  - Recent: `state/RecentItemsState` (project workspace file, newest first, 50 max; kinds TABLE, CONSOLE, SCRIPT), recorded in `DatabaseEditorManager.openTableData`/`openConsole` and `SqlScriptPanel.execute`; shown by `ui/ExplorerRecentSection` above the tree (last 4, "N more…" popup, collapsible, Delete/context menu removes; hidden while explorer search is open).
  - Search Everywhere Lattice tab: `search/LatticeSearch` (pure ranking, unit-tested) and `search/LatticeSearchContributor`; cached tables come from `MetadataCacheState.tables(config)` without columns; a connection result calls `DatabaseMainPanel.revealConnection`.
  - Environment tags: `model/Environment`, `ConnectionConfig.environment`, Environment row in `ConnectionDialog`; tree rows append "Unreachable" (when not connected and the first child is an error node) then the tag in `DatabaseUi.environmentColor` (local omitted).
  - Approved deviations from concept 04 are listed in `DESIGN.md` (decisions log) and on the artifact banner.
- Motion: `ui/ConnectionArrival` + `DatabaseTreeCellRenderer.paintComponent`; started from `loadConnectionsFromState` for newly added connection ids. Clock is injectable (`ConnectionArrival(JTree, LongSupplier)`) so previews paint frozen moments. Reduced motion = screen reader active or `RemoteDesktopService.isRemoteSession()` (the IDE has no reduced-motion setting; `UISettings.getAnimateWindows()` is a registry flag off by default, do not use it).
- R3 explorer slice: `ui/ExplorerBanner` (now `ErrorBanner`) (platform `InlineBanner`, one at a time, first line of the driver message + Retry / Edit connection / Details) replaces the explorer's four error modals (failed open of a cached table, failed connection expand, SQL generation failure, failed create/drop). A successful load of the same connection clears it. The Remove confirmation stays modal on purpose.
- README images `explorer-home.png` and `connection-dialog.png` rendered on the IntelliJ 2026.2.3 Islands Light baseline.

- R3 table editor slice (T1.13), owner picked 2026-11-27: `ui/ExplorerBanner` renamed `ui/ErrorBanner` (padding argument, `warning` variant) and placed under the table editor's filters. It replaces the 10 error/warning modals in `TableDataEditorPanel`: load/filter failure (Retry, Clear filter when a filter is set; auto-refresh failure also shows it), truncate, CREATE TABLE fetch (Retry), Cannot delete, delete failure (Refresh), Cannot commit, commit failure (no Retry on purpose), truncated-values export (Export all persisted rows), export failure (Retry). The validation modal became a plain return (Commit is disabled while cells are invalid and the footer names them). Footer messages lost their "· See error details" suffix. UI tests now wait for the banner instead of pressing OK on "Commit Error" / "Data Fetch Error".
- Explorer duplicate: a failed connection expand no longer repeats "Could not load databases" in the footer while the banner shows (footer returns to Ready); background refreshes with no banner keep the footer error. The red tree row stays (owner, 2026-11-27).

## Decided (owner, 2026-11-27)
- T0.8 red inspector Cancel stays as documented.

## Verification status
- `./gradlew test`: passed. Full real-IDE review run (light, `-Plattice.ui.review=true`): passed end to end after the fixes below; `table-filter-error.png` and `table-commit-failure.png` checked (in git-ignored `.temp/reviews/table-banner/`).
- Fixed on the way: stale "Welcome to Lattice" lookup in `assertExplorerOverflow`; `ConnectionDialog` clipped buttons (UI hierarchy showed Swing bounds 516 px vs a 487 px native window after a resize while shown). `refreshFormSize()` now packs synchronously before show and, once shown, refits once on the next event and skips no-op resizes. The failure was intermittent; two full runs have passed since.
- Not rendered: dark theme, font scale 2.0, and the other banners (truncate, DDL, delete, export, auto-refresh).

## Decided (owner, 2026-11-27, later)
- Table editor footer no longer repeats the banner: load (and auto-refresh), truncate, CREATE TABLE fetch and export failures return it to "Ready" (`statusReady()`); delete and commit keep their consequence lines.

## Next
- Verify the above, then the remaining success modals: table editor (Table truncated, Changes saved, Export complete) and explorer (Created/Dropped/Table created/Table dropped/Table truncated; UI tests press OK on "Table created").
- Remaining R3 error modals: dry runs (`sql/run/DryRunRunner`, 4), `ConnectionDialog` (3), `RunQueryAction`, `DatabaseEditorManager`, `CreateTableDialog`, `AlterTableDialog` (1 each).
- Then milestone 0.3 data editor, starting with the T1.1 grid model spike (`ROADMAP.md`).

## Known gaps and findings
- Narrow tool windows clip the tree row's tag; the tooltip carries it (approved).
- The arrival tint covers the row's icon and text, not the full tree width (a cell renderer paints only its bounds).
- `DatabaseUi.SECONDARY_TEXT` may fall below 4.5:1 on the light panel (estimate, never measured; check on a render).
- Font scale 2.0 was checked only in headless previews (one doubled font, icons unchanged); the real-IDE harness scales components, not fonts.

## Open questions
See `ROADMAP.md` → Open questions (Settings Sync roaming, minimum IDE, licensing).

## Notes for the next session
- Gradle in cloud sessions: Maven Central answers 429. `~/.gradle/init.d/mirror.gradle.kts` (from `docs/CLOUD_BUILDS.md`) must exist; `--offline` works once caches are warm. Unit tests: `./gradlew test`.
- Headless previews (`python scripts/ui-preview.py`) download five jars from Central; if that 429s, pre-fetch them from `https://maven-central.storage-download.googleapis.com/maven2` into `~/.cache/lattice-development/` at `tools/junit/junit-4.13.2.jar`, `tools/junit/hamcrest-core-1.3.jar`, `tools/flatlaf/flatlaf-3.8.jar`, `jdbc/hsqldb/hsqldb-2.7.4.jar`, `jdbc/mysql/mysql-connector-j-26.10.0.jar` (checksums are verified by the script).
- `--home-only` previews every explorer-home state, the Search Everywhere result list, the arrival frames (0/80/240/400/700 ms) and font 2.0: `python scripts/ui-preview.py --home-only --output .temp/ui-preview-home`. These render on the 2025.1 SDK and are not the tracked-image baseline.
- Tracked images: render on IntelliJ 2026.2.3 (download with `scripts/dependencies.py` `Dependencies.ide(version='2026.2.3')` and `java(major=25)`), with `org.gradle.java.installations.paths=<jdk-25>` in `~/.gradle/gradle.properties` (a `-P` flag also works; `GRADLE_OPTS` and `ORG_GRADLE_PROJECT_` do not). Then `python scripts/ui-preview.py --ide-home <idea-2026.2.3> --java-home <jdk-25>`. Re-rendering unchanged screens shifts glyphs by a pixel; commit only images whose screens changed and `git checkout` the rest.
- Real-IDE captures: `LATTICE_UI_SKIP_REMOTE_DOWNLOADS=true LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh -Plattice.ui.output=$PWD/.temp/reviews/<name>` plus `-Plattice.ui.theme=ExperimentalLight` and a focused flag (`homeOnly`, `explorerSearchOnly`, `sourceFilterOnly`; `cellDetailsOnly`, `sqlEditorOnly`, `sqlScriptOnly` also need `-Plattice.ui.review=true`). Failure screenshots and the UI hierarchy land in the ignored `out/ide-tests/.../log/`.
- `homeOnly` scenario: first run, two tables + console into Recent, an unreachable MySQL connection (banner asserted, no modal), environments set through Edit connection, Search Everywhere Lattice tab. Its create-table step ("combo selection 'TIMESTAMP'") failed once and passed on rerun: flaky keyboard combo selection, unrelated to the home code.
- UI test lookups: `x { … }` needs exactly one match (the first-run state has several `ActionLink`s); `InlineBanner` wraps its text, so match the component and a short substring, not the full sentence; tree paths match the full rendered row text, so tag or status suffixes break `explorerRow` paths (create connections untagged, tag them last).
- Search Everywhere highlights matches on the selected row only; that is the platform (`ide.highlight.match.in.selected.only`), not a bug.
- Xvfb captures show subpixel color fringes on text (dark red/cyan); text is black.
- The UI-scale-2.0 full run fails at an "Available drivers" combo wait: the 1920x1080 Xvfb screen is 960x540 logical at 2x; scaled runs need a larger screen.
