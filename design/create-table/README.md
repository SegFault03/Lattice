# Create table: three Swing concepts

Concept **01 · Column Composer** was selected and is now implemented in
[`CreateTablePanel`](../../src/main/java/com/segfault03/ideadb/dialog/CreateTablePanel.java)
and [`CreateTableDialog`](../../src/main/java/com/segfault03/ideadb/dialog/CreateTableDialog.java).
See [the implemented dialog](final/README.md) and
[native review instructions](../../docs/UI_PREVIEW.md#column-composer).

The images preserve the design study. Open [the comparison gallery](index.html) to switch between all three concepts
and light/dark themes. The [light](renders/overview-light.png) and
[dark](renders/overview-dark.png) sheets show the directions side by side.

| Concept | Layout | Best for | Trade-off |
| --- | --- | --- | --- |
| 01 · Column Composer | Column navigator and property inspector, with SQL below | Consistency with the chosen alter-table dialog | Less direct comparison between columns |
| 02 · Schema Canvas | Full schema grid, selected-column editor and SQL | Defining and comparing many columns | Some fields appear in both the grid and editor |
| 03 · Blueprint | Ordered column cards beside a persistent SQL sheet | Reviewing the complete table while editing in place | More scrolling as expanded definitions grow |

**Suggested direction: 01.** Creating and altering a table share the same column
editing model. Table naming and creation-specific keys/identity controls fit
around it. All three concepts use the same six-column `shop.customers` fixture,
with `email` selected, so the layout comparison uses identical content.

## Scope

These are presentation-only POCs. `CreateTableConcepts.java` is isolated from the
plugin source set and uses existing `DatabaseInputs`, `DatabaseUi`,
`DatabaseTable` and `Icons` for controls, sections, toolbars, key markers and SVGs.
The concepts have no action listeners, schema management, SQL generators, validation,
database connections or execution callbacks. Editable Swing controls show fixed
initial values; they do not update other parts of the prototype.

The current production dialog is rendered for reference using the same fixture.
Its existing SQL builder supplies that baseline preview. The functioning production Composer is separate from these archived concepts.

Every PNG is actual Swing component painting at 2× density with JDK 21 and the
IntelliJ 2025.1 test application. FlatLaf 3.7.2 approximates the light and dark IDE
themes; these are not live IntelliJ window captures. The HTML gallery only
switches between those renders.

## Re-render

From the repository root:

```sh
python design/create-table/render.py --java-home /path/to/jdk-21 --gradle-user-home /path/to/gradle-cache
```

The helper accepts `--ide-home`, `--flatlaf-jar`, `--output` and the shared
dependency options from `scripts/ui-preview.py`. Build scratch is isolated under
ignored `build/create-table-pocs/`; PNGs go to `design/create-table/renders/`.
The renderer leaves the tracked README screenshots untouched.
