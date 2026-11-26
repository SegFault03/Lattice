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

## In progress
- Milestone 0.2.x (T0.1–T0.12 in `ROADMAP.md`).

## Remaining
- Everything after T0.x in `ROADMAP.md`.

## Open questions
See `ROADMAP.md` → Open questions (Settings Sync roaming, minimum IDE, licensing).

## Notes for the next session
- Real-IDE captures: `LATTICE_UI_SKIP_REMOTE_DOWNLOADS=true LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh -Plattice.ui.review=true -Plattice.ui.output=$PWD/.temp/reviews/<name>`; add `-Plattice.ui.theme=ExperimentalLight`, `-Plattice.ui.scale=2.0` or a focused flag (`-Plattice.ui.cellDetailsOnly=true`, `sqlEditorOnly`, `sqlScriptOnly`, `explorerSearchOnly`, `sourceFilterOnly`). Remote driver downloads fail from cloud sessions (Maven Central).
- `ide.ui.scale=2.0` in the harness scales components but not IDE fonts; font scale 2.0 is still untested.
- The UI-scale-2.0 full run fails at an "Available drivers" combo wait, and `sourceFilterOnly` fails waiting for 7 filtered rows (T0.11).
