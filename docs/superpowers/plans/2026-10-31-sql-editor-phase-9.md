# SQL Editor Phase 9 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Send to Console, parameter preview with remembered values, and a safe dry-run (Explain; Run and Roll Back for DML) for SQL in `.sql` files and in Java/Kotlin strings with injected SQL.

**Architecture:** A new `com.segfault03.ideadb.sql.run` package holds the pure logic: statement range, parameters, literal rendering, statement kind. It also holds the IDE glue: target resolution, dialog, actions, the Dry Run tab. A new `service/DryRunService` runs plans and rolled-back DML on a dedicated connection with values bound through `PreparedStatement`. Send to Console renders values as literals and reuses `DatabaseEditorManager.openConsole`; the console is unchanged.

**Tech Stack:** Java 21, IntelliJ Platform 2025.1 SDK, JUnit 5 (unit), `BasePlatformTestCase` via JUnit vintage (fixtures), H2 2.3.232 and HSQLDB 2.7.4 in memory, MySQL Connector/J and pgjdbc for live suites.

**Spec:** `docs/superpowers/specs/2026-10-31-sql-editor-phase-9-design.md`

## Global Constraints

- Parameters are `?`, `:name` and `$1`. MySQL `@name` is a session variable, never a parameter.
- Mixing positional (`?`, `$1`) and named (`:name`) parameters in one statement is refused.
- Value types: text, number, boolean, date, timestamp, NULL.
- Parameter values live in workspace state (`StoragePathMacros.WORKSPACE_FILE`), keyed by file URL and parameter key. Never in the repository.
- Send to Console inserts and never runs.
- Dry-run binds values with `PreparedStatement`. Only Send to Console and the dialog preview render literals.
- `EXPLAIN ANALYZE` is never sent.
- Run and Roll Back accepts `DML` only (`INSERT`, `UPDATE`, `DELETE`, `MERGE`, MySQL `REPLACE`, or `WITH` whose main verb or any CTE modifies data).
- Run and Roll Back always rolls back. Its timeout is `DataService.QueryOptions.defaults().timeoutSeconds()` (60). On MySQL, `innodb_lock_wait_timeout` is set to the same value.
- Dry-run uses a dedicated connection from `DatabaseConnectionManager.openConnection`, closed in `finally`. It never uses the console's `DatabaseSession`.
- All three actions are hidden when `UltimateDatabasePlugin.isActive()`. No default keyboard shortcuts.
- Action ids are prefixed `com.segfault03.lattice.sql.run.`.
- User-facing copy is fixed by the spec: "Select a single statement", "Only queries and DML can be explained.", "Rolled back", "Outcome unknown", "Cancelled; any changes were rolled back", "Don't ask again for this project".
- Follow the surrounding code's density and idiom (`CLAUDE.md`). Docs are current-state.
- Commit after each task. Push only with the user's approval.

## Review Focus

- **PostgreSQL `?` operators in `.sql` files** (`data ? 'k'`, `?|`, `?&`): these are jsonb operators, not parameters. A `?` is a parameter in PostgreSQL only inside an injected Java/Kotlin string, because JDBC uses `?`. Test in Task 2.
- **`:` that is not a named parameter**: MySQL `:=`, PostgreSQL `::text`, array slices `arr[1:2]`, and `:` inside strings or comments. A named parameter is `:` followed by a letter or `_` and not preceded by `:`. Test in Task 2.
- **Caret between statements**: caret right after `;`, on a blank line after the last statement, or inside a comment between statements. The statement that ends on or before the caret's line wins. A caret in leading whitespace before any statement gives none. Test in Task 1.
- **Multi-megabyte dumps**: resolving the caret statement must go through `SqlTextWindow.around` and stay under 200 ms on a 5 MB file. Test in Task 1.
- **A second dry-run on the same connection while one is running**: refused with "A dry run is already running for this connection." Re-run is disabled while it runs. Test in Task 11.

---

## Increment 1: core and Send to Console

### Task 1: Statement range

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlStatementRange.java`
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlStatementRangeTest.java`

**Interfaces:**
- Consumes: `SqlTokenizer.tokenize(CharSequence, SqlDialectProfile) -> List<SqlToken>`, `SqlTextWindow.around(CharSequence, int) -> Range`, `SqlDialects` profiles.
- Produces:
  - `record SqlStatementRange(int start, int end)`, with whitespace and trailing `;` trimmed.
  - `static @Nullable SqlStatementRange atCaret(CharSequence text, int caret, SqlDialectProfile profile)`.
  - `static List<SqlStatementRange> inSelection(CharSequence text, int start, int end, SqlDialectProfile profile)`. Statements are split only on top-level `PUNCTUATION` `;` tokens, so `;` in strings, comments and `$$` bodies never splits. Ranges that hold only comments are dropped.

- [ ] **Step 1: Write the failing tests**

`atCaretReturnsEnclosingStatement`, `semicolonInsideStringCommentAndDollarBodyDoesNotSplit` (PostgreSQL `$$`, `$tag$`; MySQL `/*! */`; H2 `//`), `caretRightAfterSemicolonPicksPrecedingStatement`, `caretOnBlankLineAfterLastStatementPicksIt`, `caretInLeadingWhitespaceGivesNull`, `selectionWithTwoStatementsReturnsTwo`, `commentOnlySelectionReturnsEmpty`, `fiveMegabyteDumpResolvesUnder200ms`. The last builds 5 MB of `INSERT ...;\n` lines, puts the caret in the middle, and asserts `elapsed < 200 ms` and the exact range.

- [ ] **Step 2: Run** `./gradlew test --tests '*SqlStatementRangeTest'`. Expected: FAIL to compile.
- [ ] **Step 3: Implement** `SqlStatementRange`. `atCaret` tokenizes only `SqlTextWindow.around(text, caret)` and offsets the tokens back.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "Resolve the SQL statement at the caret or in a selection"`.

### Task 2: Parameters

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlParameters.java`
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlParametersTest.java`

**Interfaces:**
- Consumes: `SqlTokenizer`, `LatticeSqlTokens.PARAMETER`.
- Produces:
  - `enum Style { NONE, POSITIONAL, NAMED }`.
  - `record Occurrence(String key, int start, int end)`. Key format: `"1"`, `"2"`... for `?` in order and for `$n` by its number; `":name"` for named parameters.
  - `record Result(Style style, List<Occurrence> occurrences, List<String> keys /* distinct, first-seen order */, @Nullable String error)`.
  - `static Result of(CharSequence sql, SqlDialectProfile profile, boolean jdbcHost)`. `jdbcHost` is true for injected strings; for PostgreSQL, a bare `?` counts only when it is true.
  - `error` is `"Use either positional (?, $1) or named (:name) parameters, not both"` when the statement mixes them.

- [ ] **Step 1: Write the failing tests**

`questionMarksArePositionalInOrder`, `dollarNumbersKeyByNumber`, `repeatedNamedParameterSharesOneKey`, `mysqlAtNameIsNotAParameter`, `castsAssignmentsAndSlicesAreNotParameters` (`a::text`, `@x := 1`, `arr[1:2]`), `parametersInsideStringsAndCommentsIgnored`, `postgresQuestionOperatorIsNotAParameterInSqlFiles` (`data ? 'k'`, `?|`, `?&` with `jdbcHost=false` give `NONE`), `postgresQuestionIsParameterInJdbcHost`, `mixedStylesReportError`.

- [ ] **Step 2: Run** `./gradlew test --tests '*SqlParametersTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `SqlParameters.of`. If the lexer does not emit `PARAMETER` for one of the forms, recognize it from the adjacent `OPERATOR`/`IDENTIFIER` tokens in this class rather than changing highlighting.
- [ ] **Step 4: Run.** Expected: PASS. Also run `./gradlew test --tests '*sql.lang*'` to confirm highlighting tests are unchanged.
- [ ] **Step 5: Commit** `"List positional and named SQL parameters"`.

### Task 3: Values and literal rendering

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlParameterValue.java`, `src/main/java/com/segfault03/ideadb/sql/run/SqlLiteralRenderer.java`
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlLiteralRendererTest.java`

**Interfaces:**
- Consumes: `DatabaseProviders.get(DatabaseType).sqlLiteral(Object, String)`, `SqlParameters.Result`.
- Produces:
  - `enum SqlParameterValue.Type { TEXT, NUMBER, BOOLEAN, DATE, TIMESTAMP, NULL }`.
  - `record SqlParameterValue(Type type, String text)` with:
    - `@Nullable String validationError()`: number must parse as `BigDecimal`; boolean must be `true`/`false` (any case); date `yyyy-MM-dd`; timestamp `yyyy-MM-dd HH:mm:ss[.fffffffff]`.
    - `Object jdbcValue()`: `String`, `BigDecimal`, `Boolean`, `java.sql.Date`, `java.sql.Timestamp` or `null`.
  - `static String render(CharSequence sql, SqlParameters.Result params, Map<String, SqlParameterValue> values, DatabaseType type)`:
    - Replaces each occurrence by offset, from last to first.
    - TEXT goes through `sqlLiteral(text, "VARCHAR")`. NUMBER and BOOLEAN through `sqlLiteral(jdbcValue, …)`. NULL renders `NULL`.
    - DATE renders `DATE 'yyyy-MM-dd'` and TIMESTAMP renders `TIMESTAMP 'yyyy-MM-dd HH:mm:ss…'` in every dialect.
    - Throws `IllegalArgumentException` for a missing key or an invalid value.
  - `record JdbcStatement(String sql, List<Object> bindings)` and `static JdbcStatement toJdbc(CharSequence sql, SqlParameters.Result params, Map<String, SqlParameterValue> values)` in the same class: every occurrence (`?`, `$n`, `:name`) becomes `?`, and the bindings come in occurrence order from `jdbcValue()`. pgjdbc does not accept `$n`, which is why every form becomes `?`.

- [ ] **Step 1: Write the failing tests**

- `textQuotesEscapedPerDialect` covers all four `DatabaseType`s. The input `O'Brien \ x` must give MySQL `'O''Brien \\ x'`; for the other dialects, assert the value `sqlLiteral` returns today.
- `numberBooleanNullDateTimestamp` covers every type in every dialect.
- `valueContainingParameterTextIsNotReplacedAgain`: the value `"? :x $1"` appears once in the output.
- `repeatedNamedParameterReplacedEverywhere`.
- `toJdbcRewritesEveryFormToQuestionMarksInOrder`: `:a, $2, :a` gives `?, ?, ?` with bindings `[a, 2, a]`.
- `invalidValueRejected`, with `validationError` non-null for `"1,5"` as NUMBER and `"2024-13-01"` as DATE.

- [ ] **Step 2: Run** `./gradlew test --tests '*SqlLiteralRendererTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** both classes.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `"Render SQL parameter values as dialect literals"`.

### Task 4: Run target

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlRunTarget.java`
- Test: `src/platformTest/java/com/segfault03/ideadb/sql/run/SqlRunTargetFixtureTest.java`, plus Java and Kotlin cases in `src/platformTest/java/com/segfault03/ideadb/sql/run/SqlRunTargetJavaFixtureTest.java` and `src/platformTest/java/com/segfault03/ideadb/sql/run/SqlRunTargetKotlinFixtureTest.java`. The `*JavaFixtureTest`/`*KotlinFixtureTest` suffixes route them to `javaPlatformTest`/`kotlinPlatformTest`.

**Interfaces:**
- Consumes: Tasks 1–2; `SqlDialectResolver.resolve/connection(Project, VirtualFile)`; `SqlBindingsState.hostOf(VirtualFile)`; `InjectedLanguageManager`.
- Produces:
  - `record SqlRunTarget(String sql, SqlDialectProfile profile, @Nullable ConnectionConfig connection, VirtualFile file, SqlParameters.Result parameters)`.
  - `sealed interface Resolution permits Found, Problem`, with `record Found(SqlRunTarget target)` and `record Problem(String message)`.
  - `static @Nullable Resolution resolve(Project, Editor, PsiFile)`. It must be called inside a read action and returns null when the caret is not in Lattice SQL.
  - For injected SQL, `sql` is the whole injected text (dynamic parts are already `?`), and `parameters` uses `jdbcHost=true`.
  - The selection or caret is mapped into the injected text. More than one statement gives `Problem("Select a single statement")`.

- [ ] **Step 1: Write the failing tests:** `sqlFileCaretStatement`, `sqlFileSelectionOfTwoGivesProblem`, `unboundFileHasNullConnection`, `javaConcatenationBecomesPositionalParameters` (`"SELECT * FROM t WHERE id = " + id` gives SQL ending in `= ?` with keys `["1"]`), `javaTextBlock`, `kotlinTemplate` (`"... id = $id"`), `nonSqlStringGivesNull`.
- [ ] **Step 2: Run** `./gradlew platformTest javaPlatformTest kotlinPlatformTest --tests '*SqlRunTarget*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `SqlRunTarget.resolve`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `"Resolve the SQL an editor action runs on"`.

### Task 5: Send to Console with a basic values dialog

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlParameterDialog.java`, `src/main/java/com/segfault03/ideadb/sql/run/SqlRunActionSupport.java`, `src/main/java/com/segfault03/ideadb/sql/run/SendToConsoleAction.java`, `src/main/java/com/segfault03/ideadb/sql/run/SqlRunIntentions.java`
- Modify: `src/main/resources/META-INF/plugin.xml` (group, action, intention)
- Test: `src/platformTest/java/com/segfault03/ideadb/sql/run/SendToConsoleFixtureTest.java`

**Interfaces:**
- Consumes: Tasks 3–4; `DatabaseEditorManager.getInstance(project).openConsole(ConnectionConfig, String db, String sql)` with `db = config.getDatabaseName()`.
- Produces:
  - `SqlParameterDialog(Project, SqlRunTarget, Map<String, SqlParameterValue> initial)`, a `DialogWrapper` with `@Nullable Map<String, SqlParameterValue> showAndGetValues()`. In this task it shows one row per key (key, type combo, value field, NULL checkbox), and OK is disabled while any `validationError()` is non-null.
  - `SqlRunActionSupport`:
    - `static void update(AnActionEvent)`: hidden if `UltimateDatabasePlugin.isActive()` or the target is null; disabled with the description `"Bind this file to a connection first"` when `connection == null`.
    - `static @Nullable SqlRunTarget targetOrShowProblem(AnActionEvent)`: shows a `Problem` with `HintManager.showErrorHint`.
    - `static @Nullable Map<String, SqlParameterValue> askValues(Project, SqlRunTarget)`: returns an empty map when there are no keys, without a dialog.
    - Test seam: `static void setValuesProviderForTests(BiFunction<SqlRunTarget, Map<String,SqlParameterValue>, Map<String,SqlParameterValue>>, Disposable)`.
  - Action id `com.segfault03.lattice.sql.run.sendToConsole`, text "Send to Console".
  - Group id `com.segfault03.lattice.sql.run.group`, a popup "Lattice SQL" added to `EditorPopupMenu`.
  - `SqlRunIntentions.SendToConsole`, an `IntentionAction` that delegates to the action. Its family is "Lattice SQL".

- [ ] **Step 1: Write the failing tests:**
  - `sendsCaretStatementWithoutRunning`: the console text ends with the statement, and the panel reports no execution started.
  - `sendsRenderedLiteralsFromProvidedValues`.
  - `noParametersSkipsDialog`: the provider is never called.
  - `cancelledDialogSendsNothing`.
  - `disabledWithoutConnection`.
  - `hiddenWhenUltimateActive`: replace the check through a test hook if `UltimateDatabasePlugin` has none; add one there, package-private.
  - `intentionAvailableOnStatement`.
- [ ] **Step 2: Run** `./gradlew platformTest --tests '*SendToConsoleFixtureTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the classes and register them in `plugin.xml`. Use `getActionUpdateThread() = BGT`, as `ChooseSqlConnectionAction` does.
- [ ] **Step 4: Run** the fixture test, then `./gradlew test buildPlugin verifyPluginStructure`. Expected: PASS.
- [ ] **Step 5: Commit** `"Send the SQL statement at the caret to the console"`.

## Increment 2: remembered values and preview

### Task 6: Remembered values and run settings

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlRunState.java`
- Modify: `src/main/resources/META-INF/plugin.xml` (`projectService`), `SqlRunActionSupport.askValues` (pre-fill and save)
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlRunStateTest.java`

**Interfaces:**
- Produces: `@State(name = "LatticeSqlRun", storages = @Storage(StoragePathMacros.WORKSPACE_FILE)) final class SqlRunState implements PersistentStateComponent<SqlRunState.State>`, with:
  - `Map<String, SqlParameterValue> values(String fileUrl)`.
  - `void remember(String fileUrl, Map<String, SqlParameterValue>)`, which merges and does not replace keys missing from the new map.
  - `boolean confirmRollbackRuns()` and `void setConfirmRollbackRuns(boolean)`; the default is `true`.
  - `static SqlRunState getInstance(Project)`.
  - For an injected string, the file URL is that of `SqlBindingsState.hostOf(file)`.

- [ ] **Step 1: Write the failing tests:** `remembersPerFileAndKey`, `mergeKeepsOtherKeys`, `stateRoundTripsThroughLoadState` (`getState` → serialize with `XmlSerializer` → `loadState` gives equal values), `confirmDefaultsToTrue`.
- [ ] **Step 2: Run** `./gradlew test --tests '*SqlRunStateTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `SqlRunState`, and wire `askValues` to pre-fill from it and save on OK.
- [ ] **Step 4: Run.** Expected: PASS. Extend `SendToConsoleFixtureTest` with `dialogPrefilledFromRememberedValues` and run it.
- [ ] **Step 5: Commit** `"Remember SQL parameter values per file"`.

### Task 7: Dialog preview

**Files:**
- Modify: `src/main/java/com/segfault03/ideadb/sql/run/SqlParameterDialog.java`
- Modify: `scripts/ui-preview/UiPreview.java` (parameter dialog scene, light and dark)
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlParameterDialogTest.java`

**Interfaces:**
- Consumes: `SqlLiteralRenderer.render`.
- Produces: a read-only monospace preview below the rows, re-rendered on every edit. While any value is invalid it shows the first error instead of SQL. Package-private `String previewText()` for tests.

- [ ] **Step 1: Write the failing tests:** `previewShowsRenderedSql`, `previewShowsFirstValidationError`, `nullCheckboxDisablesValueField`, `okDisabledWhileInvalid`. Construct the dialog's panel headlessly, as `CellDetailsPanelTest` constructs its panel.
- [ ] **Step 2: Run** `./gradlew test --tests '*SqlParameterDialogTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the preview, and add the preview scene to `UiPreview.java`.
- [ ] **Step 4: Run** the tests, then `python scripts/ui-preview.py --all-previews --output build/ui-phase9` and inspect `build/ui-phase9/index.html`. Expected: PASS, and the dialog renders in both themes.
- [ ] **Step 5: Commit** `"Preview SQL with parameter values in the values dialog"`.

## Increment 3: dry-run

### Task 8: Statement kind and refusal rules

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/SqlStatementKind.java`
- Test: `src/test/java/com/segfault03/ideadb/sql/run/SqlStatementKindTest.java`

**Interfaces:**
- Produces:
  - `enum SqlStatementKind { QUERY, DML, OTHER }`.
  - `static SqlStatementKind of(CharSequence sql, SqlDialectProfile profile)`:
    - Skips leading comments.
    - For `WITH [RECURSIVE]`, skips balanced CTE bodies to the main verb. Any CTE body that starts with `INSERT`/`UPDATE`/`DELETE`/`MERGE` makes the statement `DML`.
    - `QUERY` covers `SELECT`, `VALUES`, `TABLE`. `REPLACE` is `DML` only for MySQL.
  - `static List<String> targetTables(CharSequence sql, SqlDialectProfile profile)`: the unquoted, optionally schema-qualified names of the tables the statement reads or writes, from `SqlStatementAnalyzer.structure(...).refs()`.
  - `static @Nullable String explainRefusal(SqlStatementKind)` returns `"Only queries and DML can be explained."` for `OTHER`.
  - `static @Nullable String rollbackRefusal(SqlStatementKind)`:
    - `QUERY`: `"Run and Roll Back is for INSERT, UPDATE, DELETE and MERGE. Use Explain for queries."`
    - `OTHER`: `"Run and Roll Back runs only INSERT, UPDATE, DELETE and MERGE; this statement could commit or change the schema."`

- [ ] **Step 1: Write the failing tests:**
  - `selectValuesTableAreQuery`.
  - `dmlVerbs`.
  - `replaceIsDmlOnlyForMysql`.
  - `withMainVerbDecides`.
  - `dataModifyingCteIsDml`.
  - `leadingCommentsSkipped`.
  - `refusedVerbsAreOther`: parameterized over `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `SET`, `CALL`, `LOCK TABLES`, `LOAD DATA`, `COPY`, `GRANT`.
  - `refusalMessages`.
  - `targetTablesOfUpdateJoinAndInsertSelect`.
- [ ] **Step 2: Run** `./gradlew test --tests '*SqlStatementKindTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `"Classify SQL statements for dry-run"`.

### Task 9: DryRunService.explain

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/service/DryRunService.java`, `src/main/java/com/segfault03/ideadb/service/DryRunResult.java`
- Test: `src/test/java/com/segfault03/ideadb/service/DryRunExplainTest.java`, using in-memory H2 and HSQLDB as `SqlSchemaLoaderTest` does.

**Interfaces:**
- Consumes: `DatabaseConnectionManager.openConnection(ConnectionConfig)`, `QueryExecution` (`attach`/`detach`/`checkCancelled`), `SqlParameterValue.jdbcValue()`.
- Produces:
  - `sealed interface DryRunResult`, with these variants:
    - `record Plan(String text, boolean literalFallback, long elapsedMs)`
    - `record RolledBack(int rows, List<String> warnings, long elapsedMs)`
    - `record Failed(String message)`
    - `record OutcomeUnknown(String connectionName, String message)`
    - `record Cancelled(boolean rolledBack)`
  - `static String explainPrefix(DatabaseType, int mysqlMajor, int mysqlMinor, int mysqlPatch)`:
    - MySQL 8.0.16 and later: `"EXPLAIN FORMAT=TREE "`; earlier MySQL: `"EXPLAIN "`.
    - PostgreSQL: `"EXPLAIN (FORMAT TEXT, VERBOSE) "`.
    - H2: `"EXPLAIN "`.
    - HSQLDB: `"EXPLAIN PLAN FOR "`.
  - `DryRunResult explain(ConnectionConfig, SqlLiteralRenderer.JdbcStatement statement, String literalSql, QueryExecution)`:
    - `statement` comes from `SqlLiteralRenderer.toJdbc`, so the service only sees `?`.
    - The plan text is all result rows joined with `\n`, using columns tab-separated when there is more than one column.
    - When preparing fails with bindings present, it retries once with `literalSql` (from `SqlLiteralRenderer.render`) and returns `literalFallback = true`.
  - Test seam: package-private constructor `DryRunService(ConnectionOpener)` with `interface ConnectionOpener { Connection open(ConnectionConfig) throws Exception; }`.

- [ ] **Step 1: Write the failing tests:** `explainPrefixPerDialect` (including MySQL 8.0.15 → plain, 8.0.16 → TREE), `h2ExplainWithBoundParameter`, `hsqldbExplainWithBoundParameter`, `connectionClosedAfterExplain` (with a counting opener), `cancelledBeforeStartGivesCancelled`, `sqlErrorGivesFailedWithDriverMessage`.
- [ ] **Step 2: Run** `./gradlew test --tests '*DryRunExplainTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `explain`. MySQL version comes from `DatabaseMetaData.getDatabaseProductVersion()` parsed as `x.y.z`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `"Explain SQL statements on a dedicated connection"`.

### Task 10: DryRunService.runAndRollBack

**Files:**
- Modify: `src/main/java/com/segfault03/ideadb/service/DryRunService.java`
- Test: `src/test/java/com/segfault03/ideadb/service/DryRunRollbackTest.java`

**Interfaces:**
- Consumes: `DataService.getInstance().inTransaction(Connection, Operation<T>)`; `targetTables` from `SqlStatementKind.targetTables` (Task 8).
- Produces:
  - `DryRunResult runAndRollBack(ConnectionConfig, SqlLiteralRenderer.JdbcStatement statement, List<String> targetTables, QueryExecution)`. Order of operations:
    1. Open the connection.
    2. On MySQL, run the engine check and return `Failed("<table> uses the <ENGINE> engine, which cannot roll back.")` if any table is not `InnoDB` or `NDB`. Then `SET SESSION innodb_lock_wait_timeout = 60`.
    3. Run `inTransaction`, in which `setQueryTimeout(60)`, `executeUpdate`, collect warnings, and force a rollback.
    4. Close the connection.
  - A rollback is forced by throwing a private `RollbackSignal` carrying the row count and warnings out of the operation, which `inTransaction` rolls back. The signal is caught outside and returned as `RolledBack`.
  - If the rollback or the restore of auto-commit fails, the result is `OutcomeUnknown`.
  - On cancel, the result is `Cancelled(true)` when the rollback succeeded, otherwise `OutcomeUnknown`.

- [ ] **Step 1: Write the failing tests:**
  - `h2UpdateReportsRowsAndLeavesTableUnchanged`: a second connection reads the original values.
  - `hsqldbDeleteRolledBack`.
  - `failingStatementStillRollsBackAndCloses`.
  - `rollbackFailureGivesOutcomeUnknown`: a `java.lang.reflect.Proxy` connection whose `rollback()` throws.
  - `cancelDuringRunGivesCancelledRolledBack`.
  - `mysqlNonTransactionalEngineRefusedBeforeExecute`: a proxy connection that answers the `information_schema` query with `MyISAM` and records whether `executeUpdate` ran.
  - `mysqlSetsLockWaitTimeout`: the proxy records the statement.
- [ ] **Step 2: Run** `./gradlew test --tests '*DryRunRollbackTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `"Run DML in a transaction that is always rolled back"`.

### Task 11: Dry Run tab and dry-run actions

**Files:**
- Create: `src/main/java/com/segfault03/ideadb/sql/run/DryRunVirtualFile.java`, `src/main/java/com/segfault03/ideadb/sql/run/DryRunPanel.java`, `src/main/java/com/segfault03/ideadb/sql/run/ExplainStatementAction.java`, `src/main/java/com/segfault03/ideadb/sql/run/RunAndRollBackAction.java`, `src/main/java/com/segfault03/ideadb/sql/run/RollbackConfirmation.java`
- Modify: the SQL editor's Project Defaults settings page (`sql/settings`), adding the checkbox "Confirm before Run and Roll Back" bound to `SqlRunState.confirmRollbackRuns`. This is how a suppressed confirmation is turned back on.
- Modify: `src/main/java/com/segfault03/ideadb/editor/DatabaseFileTypes.java` (a `DRY_RUN` file type with the console icon), `SqlRunIntentions.java` (`Explain` and `RunAndRollBack`), `plugin.xml`, `scripts/ui-preview/UiPreview.java` (plan, rolled-back and outcome-unknown scenes)
- Test: `src/platformTest/java/com/segfault03/ideadb/sql/run/DryRunActionsFixtureTest.java`, `src/test/java/com/segfault03/ideadb/sql/run/DryRunPanelTest.java`

**Interfaces:**
- Consumes: Tasks 5, 6, 8, 9 and 10. Use `DatabaseVirtualFile(name, fileKey, fileType)` with the file key `"dryrun:" + config.getId()`.
- Produces:
  - `DryRunVirtualFile.open(Project, ConnectionConfig)` reuses the open tab for that connection.
  - `DryRunPanel`:
    - `void showRunning(SqlRunTarget, String kindLabel, Map<String,SqlParameterValue>)` and `void show(DryRunResult)`.
    - The header shows the connection with its type icon, the schema, the kind, and the values as entered.
    - The buttons are Copy, Re-run and Send to Console. Re-run is disabled while running.
  - `boolean DryRunPanel.isRunning()`. A second run while one is running is refused with `"A dry run is already running for this connection."`.
  - `RollbackConfirmation.confirm(Project, ConnectionConfig, SqlStatementKind)`:
    - Returns `true` without a dialog when `SqlRunState.confirmRollbackRuns()` is false.
    - The dialog lists the three caveats from the spec verbatim and has the checkbox "Don't ask again for this project".
  - The actions:
    - Resolve the target, then apply the refusal from Task 8 (`Messages.showErrorDialog`, no connection opened).
    - Ask for values, then (Run and Roll Back only) confirm.
    - Run in a `Task.Backgroundable` with cancel wired to `QueryExecution.cancel()`, and post the result to the panel on the EDT.
  - Action ids: `com.segfault03.lattice.sql.run.explain` ("Explain Statement") and `com.segfault03.lattice.sql.run.rollback` ("Run and Roll Back"), in the group from Task 5.

- [ ] **Step 1: Write the failing tests:**
  - `DryRunPanelTest`: `planShownMonospace`, `rolledBackShowsRowsAndBadge`, `outcomeUnknownShowsBanner`, `cancelledCopy` (both spec strings), `rerunDisabledWhileRunning`.
  - `DryRunActionsFixtureTest`, against the H2 fixture from `H2SqlFixtureTestCase`:
    - `explainOpensTabWithPlan`.
    - `rollbackRefusesSelectWithoutConnecting`: a counting opener is installed through the Task 9 seam, using a package-private `DryRunService.setInstanceForTests(DryRunService, Disposable)`.
    - `rollbackAsksConfirmationUnlessSuppressed`.
    - `tabReusedPerConnection`.
    - `secondRunWhileRunningRefused`.
    - `actionsHiddenWhenUltimateActive`.
    - `projectSettingsCheckboxReenablesConfirmation`.
- [ ] **Step 2: Run** `./gradlew test --tests '*DryRunPanelTest'` and `./gradlew platformTest --tests '*DryRunActionsFixtureTest'`. Expected: FAIL.
- [ ] **Step 3: Implement**, register the actions and intentions, and add the preview scenes.
- [ ] **Step 4: Run** both test commands, then `./gradlew test platformTest javaPlatformTest kotlinPlatformTest buildPlugin verifyPluginStructure` and `python scripts/ui-preview.py --all-previews --output build/ui-phase9`. Expected: all PASS, and the scenes render in both themes.
- [ ] **Step 5: Commit** `"Add Explain and Run and Roll Back with a Dry Run tab"`.

### Task 12: Live database checks

**Files:**
- Create: `src/test/java/com/segfault03/ideadb/service/DryRunLiveIntegrationTest.java`, tagged `@Tag("integration")` so it runs in `integrationTest` (suite `backend-live`). It reads the same `lattice.test.*` system properties and owned fixtures as `DatabaseCompatibilityTest`.

**Interfaces:**
- Consumes: Tasks 9–10.

- [ ] **Step 1: Write the tests:** `postgresExplainWithBoundParameter`, `mysqlExplainWithServerPreparedStatements` (`useServerPrepStmts=true`), `mysqlMyIsamRefused`, `mysqlLockWaitTimeoutApplied` (reads `@@session.innodb_lock_wait_timeout` inside the run through a test hook), and `postgresUpdateRolledBack`. Each creates and drops its own table in the fixture schema.
- [ ] **Step 2: Run** `./gradlew integrationTest --tests '*DryRunLiveIntegrationTest'` against owned MySQL and PostgreSQL fixtures (`docs/CONTRIBUTING.md`). Expected: PASS. If the bound-parameter tests show both dialects accept bindings, remove the literal fallback from Task 9 together with its `literalFallback` field and tests. If the fixtures are not available in the session, record that the suite was not run and leave it for CI.
- [ ] **Step 3: Commit** `"Check dry-run against live MySQL and PostgreSQL"`.

### Task 13: Documentation

**Files:**
- Modify: `docs/SQL_EDITOR.md` (a new "Running statements" section after "Hover details"), `docs/CHANGELOG.md` (Unreleased), `README.md` (feature list)

- [ ] **Step 1: Write** the section, covering:
  - The three actions and where they are.
  - Parameter rules, including `@name`, PostgreSQL `?` in `.sql` files, and no mixing of styles.
  - That values are remembered per file in workspace state.
  - The Explain statement per dialect.
  - The refusal rules, the MySQL engine check, the caveats, how to re-enable the confirmation, and the result states.

  Write it as current state, in the table style of the surrounding sections.
- [ ] **Step 2: Run** `python -m unittest discover -s scripts/tests -v` and `python scripts/one-shot-test.py --check-workflow`. Expected: PASS, so doc link and layout checks hold.
- [ ] **Step 3: Commit** `"Document running statements from the SQL editor"`.
