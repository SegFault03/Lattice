# Hand-off

Delete before a release (see `CLAUDE.md`).

## Goal
Take Lattice from 0.2.0-alpha to 1.0 as the data tools layer for IntelliJ IDEA without the Ultimate subscription. Plan, task list and milestones: `ROADMAP.md`.

## Decisions (owner, 2026-11-26)
- Positioning: data tools layer; reuse the IDE's free data sources where available; own connections for 2025.1–2025.2 and when the Database plugin is off.
- 1.0 engines: MySQL, MariaDB, PostgreSQL, SQLite, H2, HSQLDB.
- Work on `claude/workspace`; push that branch at checkpoints.
- This session: roadmap, then milestone 0.2.x fixes, then UI concepts for the owner's pick. Rewrites (R1–R7) wait for concept picks and spikes.

## Done
- Full code and real-IDE UI review (IntelliJ 2025.1, dark and light, UI scale 2.0, focused inspector/SQL editor/script/search runs). Report and captures are local only in git-ignored `.temp/reviews/` (may hold private content; not committed).
- Competitor and free-tier research; `ROADMAP.md`.

## Done in milestone 0.2.x
- T0.1–T0.7, T0.9–T0.12 (see `ROADMAP.md`). Verified on the real IDE (dark, full run, 100 captures): Cancel default on Drop table, NULL italic in new rows, refit columns, 4.84:1 welcome subline, cell menu targets the clicked cell, source filter run passes.
- Fixed along the way: cell context menu acted on the previous selection; newly added connections stayed hidden in a collapsed type group; `SqlColorConsistencyTest` did not know `SqlScriptPanel`; the create/alter-table study renderers no longer compiled.

## Done: welcome as home (R7, T5.1), accepted 2026-11-26
Owner picked concept 04 Explorer home (artifact https://claude.ai/artifact/SzpE9pCdFyDxAyewLuDbnK; source `design/welcome-home/`), with 03's ranked search as a Search Everywhere tab and 02's environment tags and status in the tree. Owner answers (2026-11-26):
- Order: A+B (remove the welcome tab; first-run empty state; Recent section in the tool window), then C (Search Everywhere tab), then D (tags and status).
- Welcome tab removed completely: `WelcomePanel`, `WelcomeVirtualFile`, the `showWelcomeScreen` setting. The explorer's help button opens What's New.
- Status shows only what Lattice already knows (open sessions, last failed open); never contact a server on its own.
- Environment tags: fixed set (none, local, development, staging, production).

Done (A+B):
- Welcome tab, `WelcomePanel`, `WelcomeVirtualFile` and the `showWelcomeScreen` setting removed; the What's New file type is `DatabaseFileTypes.RELEASE_NOTES`. The explorer's help button opens What's New.
- First run: `EmptyConnectionPanel` shows "No connections yet" and one `ActionLink` per `DatabaseType`, each opening the connection dialog for it.
- `RecentItemsState` (project service, workspace file, newest first, 50 max), recorded in `DatabaseEditorManager.openTableData`/`openConsole`; `ExplorerRecentSection` above the tree: last 4, "N more…" popup with speed search, collapsible header (persisted), double-click/Enter opens, Delete or context menu removes, Clear Recent. Hidden while explorer search is open and when empty.
- Previews: `python scripts/ui-preview.py --home-only --output .temp/ui-preview-home` renders every state in both themes and first run/returning at font 2.0 (2025.1 SDK, headless; not the tracked-image baseline).

Done (C): `search/LatticeSearch` (pure ranking: recents, then cached tables by match degree capped at 50 and never repeating a recent table, then connections with an "Open SQL console" action, then data source names) and `LatticeSearchContributor` (separate Lattice tab; Enter opens, Alt+Enter console; connection selects it via `DatabaseMainPanel.revealConnection`). Captured in the real IDE (`-Plattice.ui.homeOnly=true`, light and dark). Matches are highlighted on the selected row only, the platform's convention (`ide.highlight.match.in.selected.only`).

Done: SQL scripts become recent items when a run starts (`SqlScriptPanel.execute`); reopening shows `SqlScriptDialog` with the file chosen.

Done (D): `model/Environment` and `ConnectionConfig.environment` (Environment row in the connection dialog); the tree row appends "Unreachable" (error color) when the connection's first child is an error node and it is not connected, then the tag in `DatabaseUi.environmentColor` (local omitted); tooltip carries both. Search Everywhere connection context adds the tag.

Deviations from concept 04 (approved by the owner at accept):
- At narrow widths tree rows clip the tag at the edge (the concept flagged this); the tooltip carries it. No connected/checking word is shown: the plug icon and bold name already say connected, and Lattice never pings.
- Search Everywhere puts the muted context right after the title (platform convention), not right-aligned.
- Recent is fixed above the tree and does not scroll with it (matches the concept's long-list note, not a single scroll).
- Only the four existing engines; MariaDB and SQLite appear when their providers exist.
- The tree keeps its visible "Data Sources" root; tags and status in tree rows are part of D.
- The arrival motion (new connection rises into the tree) waits for the owner to confirm the motion defaults.

Remaining for R7: none.
- Accepted; `accept` ran: `design/welcome-home/` deleted, direction, tokens, motion status, bans and decision recorded in `DESIGN.md`, artifact republished with a Chosen banner.
- Motion confirmed and the arrival moment built (`ConnectionArrival`).
- R3 for the explorer (owner chose explorer first): its 4 error modals now go to `ExplorerBanner`. Left as modals on purpose: the Remove confirmation (destructive). Still modal and next in line: the explorer's success messages (Created/Dropped/Table created/truncated; UI tests press OK on them), then the table editor's 10 errors (T1.13).

## Waiting for the owner
- T0.8 red inspector Cancel: documented design choice in `docs/UI_PREVIEW.md`; change only on the owner's word.

## Design workflow
- UI work follows `DESIGN.md` and the `design-primer` skill (`anthropic-skills:design-primer`; the repo has no `.claude/skills/` copy). Concepts: every state, light and dark, font 1.0 and 2.0, motion rows, the skill's concept viewer, renders only in `.temp/`.
- Findings from the rebuild: `DatabaseUi.SECONDARY_TEXT` may fall below 4.5:1 on the light panel (estimated from an assumed gray, not measured; check on a render); MariaDB and SQLite need icons.

## Next
- Milestone 0.3 data editor, starting with the T1.1 grid model spike.

## Remaining
- Everything after T0.x in `ROADMAP.md`.

## Open questions
See `ROADMAP.md` → Open questions (Settings Sync roaming, minimum IDE, licensing).

## Notes for the next session
- Baseline renders in a cloud session: the IDE and JDK 25 download with `Dependencies.ide(version='2026.2.3')` / `java(major=25)` into `~/.cache/lattice-development`; Gradle needs `org.gradle.java.installations.paths=<jdk-25>` in `~/.gradle/gradle.properties` (a `-P` flag also works; `GRADLE_OPTS` and `ORG_GRADLE_PROJECT_` do not). Re-rendering unchanged screens here shifts glyphs by a pixel; commit only the images whose screens changed.
- Real-IDE captures: `LATTICE_UI_SKIP_REMOTE_DOWNLOADS=true LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh -Plattice.ui.review=true -Plattice.ui.output=$PWD/.temp/reviews/<name>`; add `-Plattice.ui.theme=ExperimentalLight`, `-Plattice.ui.scale=2.0` or a focused flag (`-Plattice.ui.cellDetailsOnly=true`, `sqlEditorOnly`, `sqlScriptOnly`, `explorerSearchOnly`, `sourceFilterOnly`, `homeOnly`). Remote driver downloads fail from cloud sessions (Maven Central).
- `ide.ui.scale=2.0` in the harness scales components but not IDE fonts; font scale 2.0 is still untested.
- The UI-scale-2.0 full run fails at an "Available drivers" combo wait: at 2x the 1920x1080 Xvfb screen is only 960x540 logical, so popups open off-screen. `scripts/linux/capture-intellij-ui.sh` fixes the Xvfb screen at 1920x1080; scaled runs need it enlarged (for example 3840x2160).
