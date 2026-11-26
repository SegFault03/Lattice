# DESIGN.md: Lattice

<!-- Created by `init`, kept by `create`, `refine` and `accept`. This is the one place for this project's UI/UX rules. Project facts: under 120 lines. Write from what is built, not from intent. Every line is read from the repo or stated by the owner. -->

## How to work on UI here
For any UI/UX task, read this file, then load the design-primer skill (`anthropic-skills:design-primer`, or `.claude/skills/design-primer/SKILL.md` in a checkout that has it; paths in the skill are relative to its own folder). If neither is available, stop and tell the owner rather than working without it. Project rules in this file override the skill.

| Task | Command (file in the skill folder) | Reads |
|---|---|---|
| Set up or refresh this file | `commands/init.md` | stack file, this template |
| New screen, redesign, concepts | `commands/create.md` | inventory, design, concept-sheets, motion, platform, craft-floor |
| Critique or audit, no edits | `commands/review.md` | inventory, review, platform, craft-floor |
| Targeted pass (polish, harden, typeset...) | `commands/refine.md` | refine, the pass's topic file, craft-floor |
| List every UI surface | `commands/inventory.md` | inventory, platform |
| Owner accepted the build | `commands/accept.md` | this file |

Always: real renders with real data, the skill's coverage matrix, motion designed in, verify on the real render, concepts published as an artifact, local renders git-ignored.

## Project overrides
- Cloud sessions have no emulator or device: verify UI with the headless Swing previews (`python scripts/ui-preview.py`, `docs/UI_PREVIEW.md`) and say so. [from CLAUDE.md]
- Before wiring a UI change, produce one to three concepts and get the owner's opinion. [from CLAUDE.md]
- Tracked images are rendered headlessly on IntelliJ 2026.2.3 in Islands Light with the default Periwinkle schema colors (`docs/UI_PREVIEW.md#baseline-for-tracked-images`). [from CLAUDE.md]
- Design studies follow `design/README.md`: concepts stay out of the plugin source set, reuse production parts (`DatabaseInputs`, `DatabaseUi`, `DatabaseTable`, `Icons`), add no behavior, share one fixture, include a production baseline, and label renders as Swing painting at 2x with FlatLaf approximating the IDE themes. [from design/README.md]

## Platform and stack
- Platform: IntelliJ Platform plugin, Java Swing with IntelliJ UI classes (`JBColor`, `JBUI.scale`); compiled against IntelliJ 2025.1 and 2026.3. [from CLAUDE.md]
- Component library: IntelliJ Platform UI; icon set: bundled SVGs in `src/main/resources/icons/` (29 files, 8 `_dark` variants) and `META-INF/pluginIcon*.svg`.
- Shared UI helpers: `src/main/java/com/segfault03/ideadb/ui/DatabaseUi.java`, `DatabaseInputs.java`, `Icons.java`.

## Users and job
- TODO(owner): who uses it, in what situation.
- Database client for MySQL, PostgreSQL, HSQLDB and H2 inside IntelliJ IDEA. [from CLAUDE.md]

## Surface modes (proposed; full inventory via `inventory`)
| Surface | Mode |
|---|---|
| Explorer tool window (`Lattice`, `DatabaseMainPanel`, tree, search, toolbar) | Operate |
| Table data editor (`TableDataEditorPanel`, filter, cell details) | Operate |
| SQL console and SQL editor (cards, dry run, run actions, status widget) | Operate |
| Connection dialog, create/alter table, create database, SQL script dialogs | Operate |
| Explorer home (`EmptyConnectionPanel` first run, `ExplorerRecentSection`) | Operate |
| Search Everywhere Lattice tab (`LatticeSearchContributor`) | Operate |
| What's New page (`ReleaseNotesPanel`) | Read |
| Plugin icon, Marketplace assets | Persuade |

## Chosen direction
- Established world in code: the IDE's own look. Colors come from `JBColor` pairs and named IDE keys (for example `Button.default.startBackground` in `DatabaseUi.java`), sizes go through `JBUI.scale`, the What's New page follows the current IDE theme.
- Explorer home (chosen 2026-11-26): Lattice owns no editor tab. Home is the tool window (first-run links per data source; a collapsible Recent section of the last four tables, views, consoles and scripts above the tree) plus a Lattice tab in Search Everywhere (recents, tables in loaded schemas, connections; Enter opens, Alt+Enter opens a console). Thesis: the editor area belongs to the user's code; the IDE already has a home for tools (tool windows) and for jumping (Search Everywhere).
- Status shows only what Lattice already knows (open session, last open failed); it never contacts a server to show status. Environment tags (local, development, staging, production) sit beside the connection name in their own color; local stays unmarked.

## Tokens
- Location in code: `src/main/java/com/segfault03/ideadb/ui/DatabaseUi.java` (action colors `POSITIVE_ACTION_COLOR`, `PRIMARY_ACTION_COLOR`, button metrics, rounded fills of 10 and 6 scaled px).
- Environment tag colors: `DatabaseUi.environmentColor(Environment)`, light/dark pairs with at least 4.5:1 on the panel backgrounds (production 0xAD241D/0xF2B8B5, staging 0x855000/0xF0BE6E, development 0x2A56A3/0xB4CEFB, local 0x3D6829/0xB4D9A3).
- Spacing in the explorer home: 4-based (4, 8, 12, 16, 24), inline in `EmptyConnectionPanel` and `ExplorerRecentSection`. Radii, elevation: no token file; values are inline in `DatabaseUi`.
- Dark theme: `JBColor` light/dark pairs and `_dark` SVG variants. Dynamic color: not applicable.

## Fonts and licenses
- No bundled font files. Text uses IDE fonts; SQL highlighting follows the IDE editor scheme (`SqlSwingHighlighter`).

## Motion scheme
- No animation framework found. Timers exist only for delays: hover cards (`SqlCardHoverController`), alter-table preview (300 ms), copied hint (1800 ms), copy hover (120 ms).
- Explorer home ships without animation: lists, filtering, selection and collapse are instant (platform behavior). Proposed default, not yet confirmed by the owner: quiet and IDE-native, feedback 100-150 ms, state changes 150 ms, ease-out-quart, one authored moment per surface (a new connection rising 8 px into the tree), reduced motion keeps opacity and color only. Record it here once confirmed.

## Banned in this project
- A plugin-owned editor tab as a home or marketing page; marketing heroes and card grids on Operate surfaces.
- Status by color alone (every status has its word: Unreachable, tag text); gray text on tinted surfaces; colored bars along one side of a row.
- Pinging servers just to show status.
- Modals for routine failures such as a failed open (the explorer still raises one; roadmap R3 replaces it with an inline banner).

## Other consolidated UI rules
- Preview and screenshot output may hold private content: keep it in git-ignored `.temp/` or `build/` and never commit or upload it (the line also covers credentials and stays in CLAUDE.md). [from CLAUDE.md]
- Real-IDE screenshots come from `uiScreenshotTest` (`src/uiTest/kotlin/`) and `scripts/linux/capture-intellij-ui.sh`; they need Xvfb, `xdpyinfo` and a window manager. [from CLAUDE.md]
- Native UI tests reveal explorer paths one parent at a time and wait for lazy metadata; they must not assume fixed row numbers or automatic expansion. [from docs/UI_PREVIEW.md]

## Fixtures and real data
- Design studies use one fixture per screen (for example the six-column `shop.customers` table with `email` selected). [from design/create-table/README.md]
- README images come from `WhatsNewRendersJavaFixtureTest`; the nine tracked images live in `screenshots/`.
- Private content: renders go in `.temp/` (git-ignored).

## Concepts
- Render pipeline: `design/<screen>/render.py` compiles the plugin and `<Screen>Concepts.java` and paints every frame in light and dark. Studies keep their tracked `renders/` gallery; new studies write the primer's concept viewer into a git-ignored output folder and publish it as an artifact.
- Existing studies: `design/alter-table/`, `design/create-table/` (with `final/` native captures).
- New concept tasks: `design/<screen>/` per `design/README.md`; images may also go in `.temp/concepts/<task>/`. Publish each as a private concept-viewer artifact; delete local code and images after `accept`, the artifact stays.
- Concept artifacts: welcome as home (R7), https://claude.ai/artifact/SzpE9pCdFyDxAyewLuDbnK (chosen 04 Explorer home; concept code deleted after accept).

## Verification setup
- Headless Swing: `python scripts/ui-preview.py` (light, nine README images). Real IDE: Xvfb capture script above.
- Untested without an owner: any physical display, high-DPI hardware, the Windows native UI scripts.

## Decisions log
| Date | Decision | Why |
|---|---|---|
| 2026-11-26 | Created by `init`; UI rules moved from CLAUDE.md | One home for UI rules |
| 2026-11-26 | `init` re-run against design-primer 2.0.0: shared block, artifact-based concepts, pointer line | Match the updated skill |
| 2026-11-26 | Welcome as home: built and accepted 04 Explorer home with 03's ranked search as the Search Everywhere tab and 02's tags and status; welcome tab removed | A data tools layer should not compete with code for the editor area. Rejected: 02 Connection board (longest page, recents split per connection), 03 Command home as a tab (fallback). Deviations approved: Recent fixed above the tree, platform SE rendering, known-status only, four engines, no motion yet. Artifact: https://claude.ai/artifact/SzpE9pCdFyDxAyewLuDbnK |
