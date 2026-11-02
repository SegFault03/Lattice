# SQL cards: inline action toolbar for SQL in code and `.sql` files

The SQL actions (Run and Roll Back, Send to Console, Explain Statement, SQL Connection for This File) are reachable only from the editor context menu and intentions. This adds a card behind every piece of SQL Lattice recognizes, with a toolbar that appears on hover.

- **Where:** SQL injected into Java and Kotlin strings (single-line literals, text blocks, raw strings, concatenations) and each statement in a `.sql` file.
- **Look:** a rounded card with its own background and border, hugging the SQL text. Code lines never move.
- **Hover:** a floating icon toolbar above the card's top-right corner.

The existing context menu, intentions and their caret-based targeting do not change.

## Decisions

| Topic | Decision |
|---|---|
| Rendering | A painted `RangeHighlighter` card plus a floating toolbar popup. Block inlay headers and Code Vision were rejected: both add a line per SQL region, and Code Vision has no card |
| `.sql` files | One card per statement |
| Java/Kotlin | One card per injected Lattice SQL file. A concatenation of several literals is one card |
| Card shape | Hugs the text. A single-line literal gets a box around the string, quotes included. Multiline SQL gets a block from the leftmost to the rightmost column of its lines |
| Toolbar | Run and Roll Back, Send to Console, Copy SQL, a separator, then More |
| More | Explain Statement, SQL Connection for This File... |
| Connection chip | A "No connection" warning chip leads the toolbar only when the file has no connection, and opens the connection chooser |
| Run and Roll Back on non-DML | Shown disabled, with the existing refusal text as the tooltip |
| Copy SQL | Copies the SQL as Lattice sees it: quotes, escapes and concatenation removed, `?` for dynamic parts. Toolbar only, not added to the context menu |
| Send to Console | The existing action; it does not run the statement |
| Export | Not part of this feature |
| Setting | "Show SQL cards", on by default |
| No cards in | Lattice's own console and SQL editors, read-only viewers, diff editors, files over the large-file threshold |

## Components

### `com.segfault03.ideadb.sql.card` (new)

- **`SqlCard`** (record): host-document segments (`List<TextRange>`), SQL text, `SqlStatementKind` and statement count.
- **`SqlCardLocator`** must run in a read action and has no UI dependency. It produces `List<SqlCard>` for a `PsiFile`:
  - **Lattice SQL file:** one card per range from `SqlStatementRange.inSelection(text, 0, text.length(), profile)`.
  - **Other file:** one card per injected file whose language is Lattice SQL, found through `InjectedLanguageManager`. The segments are each shred's range inside its host, mapped to host offsets the same way `SqlRunTarget.selectionWithinOneFragment` does. For a one-line literal the segment is widened to include the quotes. The SQL text is the injected file's text, in which `SqlInjectionAssembler` has already written `?` for dynamic parts.
- **`SqlCardPass`** / **`SqlCardPassFactory`**:
  - It is a `TextEditorHighlightingPass`, registered through `TextEditorHighlightingPassFactoryRegistrar` to run after the injection-dependent passes.
  - `doCollectInformation` runs the locator in the background.
  - `doApplyInformationToEditor` replaces this editor's card highlighters on the EDT. They are kept in editor user data under one key, so stale ones are always removed.
  - It skips the editor kinds in the exclusion list, and does nothing when the setting is off, Lattice SQL is disabled or `UltimateDatabasePlugin.isActive()`.
- **`SqlCardRenderer`** (`CustomHighlighterRenderer`):
  - It paints the card: background, a 1px border, about 4px corner radius, and 5px horizontal padding.
  - The border is stronger while the card is hovered.
  - The bounds come from **`SqlCardGeometry`**, a pure helper that turns per-line x extents (from `editor.offsetToXY`) and line heights into one rectangle.
  - Paint order: after the editor background, before the selection, the caret row and the text (`getOrder()`). Verify this in the IDE first (see Risks).
  - Colours come from the new `TextAttributesKey` `LATTICE_SQL_CARD`: background colour plus effect colour for the border. It has light and Darcula defaults and is listed in `LatticeSqlColorSettingsPage`.
- **`SqlCardHoverController`**:
  - It is installed per editor through an `EditorFactoryListener`.
  - An `EditorMouseMotionListener` hit-tests the mouse against the current cards' rectangles.
  - The toolbar is shown about 150 ms after the mouse enters a card. It hides about 300 ms after the mouse has left both the card and the toolbar, and immediately on scroll, typing, focus loss or the card's highlighter becoming invalid.
  - The popup is a non-focusable, no-request-focus `JBPopup` wrapping an `ActionToolbar` for group `com.segfault03.lattice.sql.card.toolbar`. Its target component is the editor, and its data context supplies `SqlCardDataKeys.SQL_CARD`.
  - Placement: above the card's top-right corner. When that would leave the viewport, it goes inside the card at the top-right. It is always clamped to the visible area.
  - At most one toolbar per editor.
- **`CopySqlAction`**: copies `SqlCard.sql` through `CopyPasteManager` and shows a short "SQL copied" hint. Registered only in the card toolbar group.
- **`NoConnectionChipAction`**: a `CustomComponentAction`, visible only when the card's file has no connection. It runs `ChooseSqlConnectionAction`'s chooser.

### Changes to existing code

- **`SqlRunTarget`**: a new `resolve(Project, SqlCard, VirtualFile)` builds the target from the card's SQL. When the card holds more than one statement, Run and Roll Back and Explain get `Problem(SELECT_SINGLE_STATEMENT)`. Send to Console uses the whole text, as it does for an injection today.
- **`SqlRunActionSupport.update` / `targetOrShowProblem`**: when `SQL_CARD` is present in the event, the target comes from the card; otherwise from the caret, exactly as today.
- **Action `update` methods**:
  - `RunAndRollBackAction` is enabled only for DML kinds; on other kinds it is visible but disabled, with `SqlStatementKind.rollbackRefusal(kind)` as its description.
  - `ExplainStatementAction` follows the same pattern with `explainRefusal`.
  - These refusals apply only in card context. The context menu keeps its current behaviour, which reports the refusal on invoke.
- **`SqlEditorSettings`**: a new `sqlCards` field (default `true`) and `isSqlCardsEffective() = isEnabled() && sqlCards`. A checkbox in `SqlEditorConfigurable`. Changing it goes through `SqlEditorRefresh` so the daemon restarts.
- **`plugin.xml`**: the pass factory registrar, the editor factory listener, the colour key, and the card toolbar group with its More popup subgroup.

## Toolbar states

| Card | Leading chip | Run and Roll Back | Send to Console | Copy SQL | More: Explain |
|---|---|---|---|---|---|
| DML, bound | none | enabled | enabled | enabled | enabled |
| SELECT, bound | none | disabled (refusal text) | enabled | enabled | enabled |
| DDL or other, bound | none | disabled (refusal text) | enabled | enabled | disabled (refusal text) |
| Several statements, bound | none | disabled ("Select a single statement") | enabled | enabled | disabled ("Select a single statement") |
| Any, no connection | "No connection" | disabled ("Bind this file to a connection first") | disabled (same) | enabled | disabled (same) |

Parameters work as today: Send to Console and Run and Roll Back ask for values with the remembered defaults before acting.

## Risks

- **Paint order.** If `CustomHighlighterRenderer` ordering cannot put the card under the selection and the caret-row highlight, fall back to `TextAttributes` background highlighters on a low layer, with the border painted by a separate renderer. Check this in the real IDE before building the hover layer.
- **Performance.** The locator walks the injections once per daemon pass. In large DAO files the highlighter count equals the number of SQL strings, which is small next to the existing syntax highlighters.

## Testing

Unit tests in `src/test/java/com/segfault03/ideadb/sql/card/`, in the style of the existing fixture tests:

- **`SqlCardLocatorTest`:** card count and segments for:
  - a multi-statement `.sql` file, including comments and blank lines;
  - a Java single-line literal (quotes included);
  - a Java text block;
  - a Java concatenation (one card);
  - a Kotlin raw string;
  - a string marked with the "Not SQL" intention (no card).
- **`SqlCardGeometryTest`:** bounds for single-line and multiline extents, and padding.
- **`SqlCardToolbarStateTest`:** each row of the state table with `SQL_CARD` set, plus the case without the key, where caret behaviour is unchanged.
- **`CopySqlActionTest`:** the clipboard holds plain SQL with `?` for interpolated and concatenated parts.
- **`SqlCardPassTest`:** no highlighters when the setting is off, in a Lattice console editor, or with Lattice SQL disabled.

UI coverage:

- Extend `IntellijUiScreenshotTest.kt` (suite `ui-sql-editor`) with a Java file showing cards, and a hovered card with its toolbar. No new suite and no change to `scripts/test-suites.json`.
- Add a headless preview of the toolbar states to `ui-previews` if it fits the existing preview harness.

`docs/SQL_EDITOR.md` gets a short current-state section describing the cards and the setting.
