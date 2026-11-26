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

## In progress: welcome as home (R7, T5.1)
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

Done (C): `search/LatticeSearch` (pure ranking: recents, then cached tables by match degree capped at 50 and never repeating a recent table, then connections with an "Open SQL console" action, then data source names) and `LatticeSearchContributor` (separate Lattice tab; Enter opens, Alt+Enter console; connection selects it via `DatabaseMainPanel.revealConnection`). Previewed with the production renderer; the real popup is not captured yet.

Deviations from concept 04 (tell the owner):
- Search Everywhere puts the muted context right after the title (platform convention), not right-aligned.
- Recent is fixed above the tree and does not scroll with it (matches the concept's long-list note, not a single scroll).
- Scripts are not recorded as recent items yet (the concept lists them).
- Only the four existing engines; MariaDB and SQLite appear when their providers exist.
- The tree keeps its visible "Data Sources" root; tags and status in tree rows are part of D.
- The arrival motion (new connection rises into the tree) waits for the owner to confirm the motion defaults.

Remaining for R7:
- `screenshots/explorer-home.png` is the old welcome image renamed; re-render the featured images on IntelliJ 2026.2.3 Islands Light (`python scripts/ui-preview.py --ide-home <idea-2026.2.3>`).
- D: environment tag connection setting and inline status/tag in tree rows (known state only).

## Waiting for the owner
- Motion defaults proposed in the viewer (quiet, ease-out-quart, 150 ms state changes, one focal moment when the first connection arrives); record in `DESIGN.md` once the owner confirms.
- T0.8 red inspector Cancel: documented design choice in `docs/UI_PREVIEW.md`; change only on the owner's word.

## Design workflow
- UI work follows `DESIGN.md` and the `design-primer` skill (`anthropic-skills:design-primer`; the repo has no `.claude/skills/` copy). Concepts: every state, light and dark, font 1.0 and 2.0, motion rows, the skill's concept viewer, renders only in `.temp/`.
- Findings from the rebuild: `DatabaseUi.SECONDARY_TEXT` may fall below 4.5:1 on the light panel (estimated from an assumed gray, not measured; check on a render); MariaDB and SQLite need icons; environment tags would be a new connection setting.

## Next
- Milestone 0.3 data editor, starting with the T1.1 grid model spike.

## Remaining
- Everything after T0.x in `ROADMAP.md`.

## Open questions
See `ROADMAP.md` → Open questions (Settings Sync roaming, minimum IDE, licensing).

## Notes for the next session
- Real-IDE captures: `LATTICE_UI_SKIP_REMOTE_DOWNLOADS=true LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh -Plattice.ui.review=true -Plattice.ui.output=$PWD/.temp/reviews/<name>`; add `-Plattice.ui.theme=ExperimentalLight`, `-Plattice.ui.scale=2.0` or a focused flag (`-Plattice.ui.cellDetailsOnly=true`, `sqlEditorOnly`, `sqlScriptOnly`, `explorerSearchOnly`, `sourceFilterOnly`). Remote driver downloads fail from cloud sessions (Maven Central).
- `ide.ui.scale=2.0` in the harness scales components but not IDE fonts; font scale 2.0 is still untested.
- The UI-scale-2.0 full run fails at an "Available drivers" combo wait: at 2x the 1920x1080 Xvfb screen is only 960x540 logical, so popups open off-screen. `scripts/linux/capture-intellij-ui.sh` fixes the Xvfb screen at 1920x1080; scaled runs need it enlarged (for example 3840x2160).
