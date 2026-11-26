# Lattice: alpha to 1.0 roadmap

Plan file: delete it before the 1.0 release (see `CLAUDE.md`). Hand-off state lives in `HANDOFF.md`.

## Positioning

Lattice is **the data tools layer for IntelliJ IDEA without the Ultimate subscription.**

Since IntelliJ IDEA 2025.3 (the unified distribution), the free tier includes data source connections, a schema viewer and schema-aware SQL completion. The IntelliJ IDEA 2026.3 help lists these as **Ultimate-only**: query execution (consoles, scratch and `.sql` files), the data editor and viewer (table data and result sets), data import and export, schema diff, data compare, database diagrams, explain plan and SQL scripts. ([Database Tools and SQL help](https://www.jetbrains.com/help/idea/relational-databases.html), [unified IDEA FAQ](https://lp.jetbrains.com/intellij-idea-unified-faq/))

So the free user can see a schema and write SQL but cannot run it, see results, edit rows, or move data in and out. That gap is Lattice's product. On 2025.1 and 2025.2 Community, where no database support exists at all, Lattice is the whole stack.

**1.0 engines:** MySQL, MariaDB, PostgreSQL, SQLite, H2, HSQLDB.

**Principles**
1. Run, see, edit and move data with the speed of a desktop client (TablePlus, DataGrip) inside the IDE.
2. Safety first on real databases: production guard, read-only mode, previews of every write, Cancel as the default on destructive prompts.
3. Native IntelliJ: platform actions, keymaps, notifications, editor banners, Find Action. No bespoke widget systems.
4. Coexist with the IDE's free database support instead of duplicating it.

## Competitive landscape

| Product | Price | Strengths | Gaps Lattice can win on |
|---|---|---|---|
| IntelliJ free tier (2025.3+) | free | Connections, schema viewer, SQL completion, bundled | No query execution, no data editor, no import/export |
| Database Tools and SQL / DataGrip | Ultimate / $99+/yr | Complete | Paid |
| [Database Navigator](https://plugins.jetbrains.com/plugin/1800-database-navigator) | free | Mature, Oracle/PLSQL depth, ~5M downloads | Oracle-first UX, dated UI, last major update 2024, JDBC-generic for others |
| DataBaseManager (DBN fork) | free | Oracle tooling | Same lineage |
| DBeaver / Beekeeper / TablePlus | free–paid | Full desktop clients | Outside the IDE: context switch, no link to project code |

**Lattice's distinct edge, already built:** SQL inside Java/Kotlin strings with cards, Run and Roll Back, dry runs, project connection discovery from config files, staged editing with draft recovery. No free competitor has these. 1.0 must make the everyday data loop (query → results → edit → export) at least as fast as a desktop client.

## Where we are (0.2.0-alpha)

**Strong:** connection and driver management (bundled, verified downloads, local JARs); lazy explorer with offline cache; staged grid editing with drafts; create/alter table with SQL preview; script runner with transaction semantics; SQL editor with cards and dry runs; a real-IDE UI test harness.

**Review findings to fix first** (from the full code and UI review, captures in `.temp/reviews/`):
- P1 destructive prompts default to Drop/Delete/Truncate.
- P1 SQL NULL loses its marker in selected, modified and new rows.
- P1 secondary text 2.9:1 (dark) / 3.46:1 (light).
- P2 CSV export writes NULL and empty string identically.
- P2 script dialog cancels on the EDT; PasswordSafe I/O on the EDT under a lock.
- P2 grid columns fitted only on schema change; single-cell selection; no Copy in the cell menu; modal dialogs for routine errors; disabled Stage change looks enabled; welcome collapses at narrow widths; partial script failure reruns everything.

**Feature gaps against a 1.0 data tool** (confirmed by code search): no SSH tunnel, no SSL/TLS settings UI, no data import, no click-to-sort, no find in grid, no multi-row selection, no copy-as formats, no FK navigation, no record view, no read-only/production mode, explorer shows only tables/views/columns (no keys, indexes, routines, sequences, triggers), no ER diagram, no schema diff, explain shown as raw text, no Markdown/Excel export, toolbar buttons are hand-rolled `JButton`s (no keymap, no Find Action).

## Radical changes

These replace parts of the current design; each starts with a spike or concept round.

**R1. Platform actions everywhere.** Replace hand-built toolbar `JButton`s in the table editor, console, explorer and inspector with `AnAction`s in `ActionToolbar`s and registered action groups. Users get shortcuts, keymap customization, Find Action and consistent enabled/disabled states. Cost: L. Removes most of `DatabaseUi.action`/`ActionButton` and `InspectorButton`.

**R2. Grid rewrite around an explicit change model.** Split `TableDataEditorPanel` (1,729 lines) into `GridModel` (rows, columns, server-side sort and filter state), `ChangeSet` (immutable: cell edits, inserts, deletes, each with value kind TEXT / NULL / DEFAULT and validation), `GridView` (one renderer that styles by state, so NULL and DEFAULT keep their marker in every state) and `GridActions`. Enables multi-row selection, sorting, paste, undo/redo of staged edits and record view. The same `GridView` renders console results (read-only `ChangeSet`). Cost: XL.

**R3. Inline error and status banners.** One `EditorNotificationPanel`-style banner per editor tab (filter error, commit failure, connection lost, read-only) with Retry/Details, replacing 22 modal `Messages.showErrorDialog` calls and stale status lines. Cost: M.

**R4. Connection sources.** Introduce `ConnectionSource` with two implementations: Lattice's own connections, and the IDE's free data sources through an optional dependency on `com.intellij.database` (2025.3+). One connection model for the explorer, console and grid. Cost: spike S, build L. Risk: the Database plugin API is not a published stable API; isolate it behind the interface and verify per IDE release.

**R5. SQL language strategy for 2025.3+.** The IDE's free tier now has full SQL language support. On 2025.3+ with the Database plugin enabled, stop registering Lattice's `.sql` file type and attach Lattice's run gutter, cards, dry run and Send to Console to the IDE's SQL PSI instead; keep the Lattice SQL language for 2025.1–2025.2 and when the plugin is off. Removes the conflict warning and duplicated highlighting. Cost: spike M, build L.

**R6. Async credentials and one executor per connection.** `ConnectionSecrets` service with an in-memory cache and pooled-thread PasswordSafe access; a per-connection executor that owns statement cancel, timeouts, keep-alive and reconnect. Cost: M.

**R7. Welcome becomes home.** Replace the marketing page with a recent connections and recent tables list (open console, open table, run script), falling back to the source picker on first run. Concept round first. Cost: M.

## Roadmap

Each milestone is a release. Sizes: S ≤ 1 day, M 2–4 days, L 1–2 weeks, XL 2+ weeks.

### 0.2.x: stabilize (now)
- [x] T0.1 Destructive prompts default to Cancel (S); typed-name confirmation moved to T3.5
- [x] T0.2 NULL/DEFAULT marker in every grid state (S)
- [x] T0.3 CSV: NULL as empty unquoted, empty string as `""` (S)
- [x] T0.4 Script dialog cancel off the EDT (S)
- [x] T0.5 PasswordSafe off the EDT and outside the settings lock (M)
- [x] T0.6 Refit grid columns when data first arrives; keep user-resized widths (S)
- [x] T0.7 Secondary text contrast; `JBFont` for welcome type; welcome narrow layout (S)
- [~] T0.8 Inspector: one disabled style (done); neutral Cancel waits for the owner (documented design choice) (S)
- [x] T0.9 Cell context menu: Copy value, Inspect, Delete row; menu now targets the clicked cell (S). Copy as SQL literal moves to T1.4
- [x] T0.10 Script dialog: "Run from Statement N" after a stopped run; full statement and error text in a tooltip (M)
- [x] T0.11 Failed captures: `sourceFilterOnly` exposed a real bug (a newly added connection stayed hidden in a collapsed type group), fixed; the UI-scale-2.0 run needs a larger Xvfb screen (harness limit) (S)
- [x] T0.12 Error reporter: `ErrorReportSubmitter` that opens a prefilled GitHub issue (S)

### 0.3: data editor (R2, R1, R3)
- [ ] T1.1 Spike: `GridModel`/`ChangeSet`/`GridView` split behind the current UI (M)
- [ ] T1.2 Server-side sort by header click (multi-column with Shift) (M)
- [ ] T1.3 Multi-row selection; bulk delete, set NULL, set DEFAULT with one confirmation (M)
- [ ] T1.4 Copy as TSV, CSV, Markdown, JSON, SQL INSERT/UPDATE; paste multi-cell from clipboard into staged edits (M)
- [ ] T1.5 Find in grid (Ctrl+F) and quick filter from cell ("Filter by this value", "Exclude") (M)
- [ ] T1.6 Undo/redo of staged edits (M)
- [ ] T1.7 Record (transposed) view for wide rows (M)
- [ ] T1.8 FK navigation: jump to referenced row; show referencing rows (M)
- [ ] T1.9 Selection aggregates in the status bar (count, sum, avg, min, max) (S)
- [ ] T1.10 Column hide/reorder/pin; per-table layout memory (M)
- [ ] T1.11 Value editors: JSON with formatting, binary as hex/image preview, long text (M)
- [ ] T1.12 Platform actions and toolbar for the table editor; keymap entries (L, R1)
- [ ] T1.13 Inline banners for filter/commit/export errors (M, R3)

### 0.4: query workbench
- [ ] T2.1 Console on the `GridView`: one result tab per statement, pin results, compare (L)
- [ ] T2.2 Run current statement / selection / all, with statement highlighting and gutter run icons in the console (M)
- [ ] T2.3 Manual transaction mode: Commit/Rollback buttons, pending-transaction indicator, warn on close (M)
- [ ] T2.4 Searchable query history across consoles and projects; favorites/snippets (M)
- [ ] T2.5 Explain plan as a tree with cost highlighting per engine (L)
- [ ] T2.6 Edit results of single-table queries in place (M)
- [ ] T2.7 Long-running query notification with elapsed time and background run (S)
- [ ] T2.8 Spike R5: attach run/cards to the IDE SQL PSI on 2025.3+ (M)

### 0.5: connectivity, safety, engines (R6, R4)
- [ ] T3.1 SQLite provider: file picker, bundled xerial driver, dialect, tests (L)
- [ ] T3.2 MariaDB provider: driver, dialect differences, version detection, tests (L)
- [ ] T3.3 SSH tunnel (key, agent, password; jump host) (L)
- [ ] T3.4 SSL/TLS settings UI per engine (CA, client cert, verify mode) (M)
- [ ] T3.5 Production guard: connection color and label, read-only mode, confirm all writes on tagged connections, typed-name confirmation for DROP on tagged connections (M)
- [ ] T3.6 Keep-alive, auto-reconnect, per-connection init SQL and timeouts (M, R6)
- [ ] T3.7 Async credentials service (M, R6)
- [ ] T3.8 Spike R4: read IDE free-tier data sources on 2025.3+ (S), then build (L)
- [ ] T3.9 Decide Settings Sync roaming for connections (S; open question)

### 0.6: schema tools
- [ ] T4.1 Explorer: keys, indexes, foreign keys, routines, sequences, triggers (L)
- [ ] T4.2 CSV/TSV/JSON import into a table with column mapping, preview, types and a transaction (L)
- [ ] T4.3 ER diagram for a schema or selection (read-only, export PNG/SVG) (L)
- [ ] T4.4 Schema diff between two schemas or connections, with migration SQL (XL)
- [ ] T4.5 Export to Markdown and Excel (xlsx); export whole schema data (M)
- [ ] T4.6 Create/alter: indexes and foreign keys (M)

### 0.9: beta
- [ ] T5.1 Welcome as home (R7): concepts in `design/welcome-home/`, waiting for the owner's pick (M)
- [ ] T5.2 Accessibility pass: keyboard-only walk, screen reader names, high contrast theme, font scale 2.0 (M)
- [ ] T5.3 Performance budget: 100k-row tables, 500-table schemas, cold start under 200 ms of plugin time (M)
- [ ] T5.4 Docs: getting started, keyboard reference, migration notes; Marketplace listing, screenshots, video (M)
- [ ] T5.5 Compatibility matrix for all six engines and IDE 2025.1 to the latest EAP (M)

### 1.0: release
Release criteria:
- No open P0/P1; P2s triaged.
- Every inventory surface rendered in light and dark, at UI scale 2.0 and font scale 2.0, with real-IDE captures.
- Live suites green for all six engines; Plugin Verifier clean for every supported IDE.
- Destructive operations: Cancel default, preview of SQL, undo where possible.
- Plan and hand-off files removed (`ROADMAP.md`, `HANDOFF.md`); docs current-state.

## Open questions
1. Settings Sync: should saved connections roam? Today `LatticeSettings.xml` roams by default without passwords and with dangling local JAR paths.
2. Minimum IDE: keep 2025.1, or move to 2025.3 for 1.0 once R4/R5 land? Keeping 2025.1 doubles the SQL language and connection paths.
3. Licensing model for 1.0: free forever, or free core plus a paid tier (Marketplace paid plugin)? Changes how much of the roadmap ships in the free build.
