# SQL editor phase 9: send to console, parameter preview, safe dry-run

Phase 9 of the SQL editor, deferred from PR #16. It adds three actions on SQL in `.sql` files and in SQL strings injected into Java and Kotlin code:

- **Send to Console**: put the statement into the bound connection's console without running it.
- **Parameter preview**: ask for parameter values, show the resulting SQL and remember the values.
- **Safe dry-run**: show the execution plan, or run DML in a transaction that is always rolled back.

The console (`SqlQueryConsolePanel`) is not changed.

## Decisions

| Topic | Decision |
|---|---|
| Scope | One spec, delivered in three increments: send to console, parameter preview, dry-run |
| Dry-run | Explain (plan only) by default; an explicit Run and Roll Back for DML |
| Parameters | `?`, `:name` and `$1` are parameters. MySQL `@name` stays a session variable |
| Values | A values dialog with a preview, remembered per file and parameter key, also used by dry-run |
| Send to Console | Inserts the SQL and does not run it |
| Binding | Send to Console writes the values in as dialect literals, because the console runs plain text. Dry-run binds the values with `PreparedStatement` |
| Run and Roll Back confirmation | Shown every time by default; can be suppressed per project. Refusal rules and the MySQL engine check always apply |
| Changed-rows preview | Not in this phase |

## Components

### `com.segfault03.ideadb.sql.run` (new)

This package does not depend on the console or on `service/`.

- **`SqlRunTarget`** resolves the SQL an action works on:
  - The selection when there is one, otherwise the statement at the caret, otherwise the whole injected string.
  - Statements are split on top-level `;` using `LatticeSqlLexer` tokens. A `;` inside a string, a comment, `$$`/`$tag$` bodies, MySQL `/*! */` or after H2 `//` does not split.
  - In an injected string, `SqlInjectionAssembler` already writes `?` for each concatenated or templated part, so those parts become ordinary positional parameters.
  - Result: SQL text, dialect, bound connection and schema, and the source file URL.
  - A selection that holds more than one statement is rejected with "Select a single statement".
- **`SqlParameters`** lists the parameters from lexer tokens, so text inside strings and comments is never counted:
  - `?` and `$1` are positional and keyed by index.
  - `:name` is keyed by name; repeated names share one value.
  - `@name` is excluded. PostgreSQL `::` casts are not parameters.
  - A statement that mixes positional and named parameters is refused.
- **`SqlParameterValues`** is project-level persistent state stored with the connection bindings in workspace `.idea` state, never in the repository:
  - Keyed by file URL and parameter key.
  - Each value is a type and its text, or NULL. Types: text, number, boolean, date, timestamp, NULL.
- **`SqlParameterDialog`** has one row per parameter: name or index, type, value, NULL checkbox.
  - Below the rows, a read-only preview shows the SQL with the values written in as literals.
  - OK stays disabled until every value parses as its type.
  - The dialog is pre-filled from `SqlParameterValues`, and the values are saved on OK.
- **`SqlLiteralRenderer`** writes values into the SQL as literals for the target dialect, through `SqlLiterals`. It replaces parameter tokens by their token offsets and never by searching the text.
- **`SqlStatementKind`** classifies a statement as `QUERY`, `DML` or `OTHER` by its first significant keyword:
  - For `WITH`, it skips the CTE list and uses the main verb.
  - A data-modifying CTE (PostgreSQL `WITH x AS (DELETE ...)`) makes the statement `DML`.
  - `DML` covers `INSERT`, `UPDATE`, `DELETE`, `MERGE` and MySQL `REPLACE`.

### Actions

- **`SendToConsoleAction`**:
  1. Resolve the target.
  2. If there are parameters, run the parameter dialog.
  3. Write the values in as literals.
  4. Call `DatabaseEditorManager.openConsole(config, schema, sql)`. This focuses or opens the console for the connection and schema and appends the SQL, with the caret at its start.
- **`ExplainStatementAction`** and **`RunAndRollBackAction`**: resolve the target, run the parameter dialog if needed, then call `DryRunService`.

### `com.segfault03.ideadb.service.DryRunService` (new)

- `explain(config, schema, sql, bindings)` and `runAndRollBack(config, schema, sql, bindings)`.
- Each call opens a dedicated connection with `DatabaseConnectionManager.openConnection` and closes it in `finally`. The console session, and any transaction open in it, are never used.
- Values are bound with `PreparedStatement`.
- Cancellation goes through `QueryExecution`.

## Explain

| Dialect | Statement sent | Accepted kinds |
|---|---|---|
| MySQL | `EXPLAIN FORMAT=TREE` on 8.0.16 and later, otherwise `EXPLAIN` | `QUERY`, `DML` |
| PostgreSQL | `EXPLAIN (FORMAT TEXT, VERBOSE)` | `QUERY`, `DML` |
| H2 | `EXPLAIN` | `QUERY`, `DML` |
| HSQLDB | `EXPLAIN PLAN FOR` | `QUERY`, `DML` |

- `OTHER` is refused: "Only queries and DML can be explained."
- `EXPLAIN ANALYZE` is never sent, because it executes the statement.
- **Assumption to confirm in the live tests:** PostgreSQL accepts bound parameters in `EXPLAIN`, and MySQL does so with server-side prepares. If a dialect rejects them, Explain falls back to the SQL with the values written in as literals, and the result says so. The fallback is kept only if those tests show it is needed.

## Run and Roll Back

1. **Refusal rules**, checked before any connection is opened:
   - Only `DML` is accepted. DDL, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `SET`, `CALL` (procedures can commit), `LOCK TABLES`, `LOAD DATA`, `COPY` and other `OTHER` statements are refused.
   - `SELECT` is refused; Explain covers it.
2. **MySQL engine check**, on the dedicated connection before the statement runs. The statement's target tables are looked up in `information_schema.TABLES.ENGINE`. If any table uses an engine without transactions (MyISAM, MEMORY, ARCHIVE...), the run is refused and the table is named.
3. **Confirmation** names the connection and the statement kind, and lists the caveats:
   - Sequence and auto-increment values are consumed.
   - Triggers fire, including any effects outside the database.
   - Row locks are held until the rollback, and other sessions can wait on them.

   A "Don't ask again for this project" checkbox suppresses the dialog for that project only.
4. **Execution**:
   - `DataService.inTransaction` on the dedicated connection, then `executeUpdate`, then an unconditional rollback.
   - The statement timeout is the console's (60 s by default).
   - On MySQL, the session's `innodb_lock_wait_timeout` is set to the same limit.
5. **Result**: affected row count, elapsed time, `SQLWarning`s and a "Rolled back" badge.
   - If the rollback fails, the connection is closed without restoring auto-commit, which is the existing `inTransaction` behavior. The result shows "Outcome unknown" and names the connection.

## UI

- **Entry points:**
  - A **Lattice SQL** submenu in the editor popup holds Send to Console, Explain Statement, and Run and Roll Back.
  - The same three are available from `Alt+Enter` on a statement or an injected string.
- **Shortcuts:** none by default, because the obvious bindings conflict with the platform (`Ctrl+Shift+Enter` is Complete Current Statement). Users assign them under Keymap → Lattice SQL.
- **Ultimate Database plugin:** when it is active, the actions are hidden, the same as the rest of the SQL editor.
- **Dry Run tab:** one read-only **Dry Run: \<connection\>** editor tab per connection, built on `DatabaseVirtualFile` and reused across runs.
  - Header: connection with its type icon, schema, statement kind, and the bound values as entered.
  - Body: the plan text in a monospace viewer, or the rollback summary.
  - Buttons: **Copy**, **Re-run** (same SQL and values), **Send to Console**.

## Threading

- The target, tokens and injection are read in a read action before any background work starts.
- JDBC work runs in a cancellable `Task.Backgroundable`. Cancel calls `QueryExecution.cancel()`.
- The dedicated connection is always closed in `finally`.
- Nothing contacts the database on the EDT.

## Errors

| Case | Behavior |
|---|---|
| File has no bound connection | Actions disabled, with a tooltip pointing to the connection chooser |
| No resolvable statement, or several statements selected | Message at the caret: "Select a single statement" |
| Mixed positional and named parameters, or a value that does not parse | Validation inside the dialog; OK stays disabled |
| Refusal rule | Message dialog naming the rule; no connection is opened |
| MySQL engine check fails | Message dialog naming the table; the dedicated connection is closed and the statement never runs |
| Connection or SQL error | Shown in the Dry Run tab with the driver's message, in the console's error format |
| Rollback failure | Red "Outcome unknown" banner naming the connection; the connection is discarded |
| Cancelled | "Cancelled". For Run and Roll Back: "Cancelled; any changes were rolled back", or "Outcome unknown" if the rollback could not be confirmed |

## Testing

All of these run in suites that are already registered in `scripts/test-suites.json`.

- **Unit tests** (`./gradlew test`):
  - `SqlRunTarget`: statement splitting at every kind of boundary; selection versus caret; several statements selected.
  - `SqlParameters`: all three styles; repeated names; `@name` and `::`; text inside strings and comments; mixed styles.
  - `SqlLiteralRenderer`: every type in every dialect, including quotes, MySQL backslashes, NULL, booleans, dates and timestamps; a value containing `?` and `:x` to prove replacement is by token offset.
  - `SqlStatementKind`: `WITH`, data-modifying CTEs, `REPLACE`, `MERGE`, every refused verb.
  - `DryRunService` with a mock connection: rollback on success, failure and cancel; the connection is always closed; a rollback failure gives "Outcome unknown" with auto-commit not restored.
  - `SqlParameterValues`: values are stored per file and key and survive a state reload.
- **Fixture tests** (`backend-editor`):
  - Actions are enabled or disabled according to the binding.
  - Send to Console writes the right console text from `.sql` files, Java concatenations and text blocks, and Kotlin templates.
  - The dialog is pre-filled from remembered values.
- **Embedded database tests** (`backend-h2`, plus HSQLDB through the existing compatibility path):
  - Explain with bound parameters.
  - Run and Roll Back reports the right row count and leaves the table unchanged, checked over a second connection.
  - A refused statement opens no connection.
- **Live database tests** (`backend-live`, `jdbc-mysql`, `jdbc-postgres`, on owned fixtures only, per `docs/CONTRIBUTING.md`):
  - PostgreSQL `EXPLAIN` with bound parameters, and MySQL with `useServerPrepStmts`.
  - The MyISAM refusal.
  - The `innodb_lock_wait_timeout` setting.
- **UI previews** (`scripts/ui-preview.py`): the parameter dialog, and the Dry Run tab in the plan, rollback and outcome-unknown states, in light and dark themes.

## Documentation

- A "Running statements" section in `docs/SQL_EDITOR.md` covering the actions, parameter rules, refusal rules and caveats, written as current state.
- A `docs/CHANGELOG.md` Unreleased entry.
- README feature list rows.

## Delivery

Each increment passes `./gradlew test buildPlugin` and the `backend-editor` suite before the next one starts.

1. The `sql/run` core (`SqlRunTarget`, `SqlParameters`, `SqlLiteralRenderer`, a minimal values dialog) and Send to Console.
2. `SqlParameterValues`, the full parameter dialog, and its preview.
3. `SqlStatementKind`, `DryRunService`, Explain, Run and Roll Back, and the Dry Run tab.

## Out of scope

- A preview of the changed rows after Run and Roll Back. It would need `RETURNING` or a before/after `SELECT` per dialect. It could be added later for PostgreSQL and H2.
- Running statements in the console straight from the editor.
- `EXPLAIN ANALYZE`.
- Explaining or dry-running a selection of several statements or a whole script.
