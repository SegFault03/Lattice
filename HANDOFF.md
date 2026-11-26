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

## Waiting for the owner
- Welcome concepts (R7, T5.1): `design/welcome-home/`; render with `python design/welcome-home/render.py --output .temp/concepts/welcome-home` and open `.temp/concepts/welcome-home/index.html`. Recommendation: 03 Command home with 02's connection status.
- T0.8 red inspector Cancel: documented design choice in `docs/UI_PREVIEW.md`; change only on the owner's word.

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
