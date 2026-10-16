# Alter table: three Swing concepts

Concept **01 · Column Inspector** was selected and is implemented in
[`AlterTablePanel`](../../src/main/java/com/segfault03/ideadb/dialog/AlterTablePanel.java)
and [`AlterTableDialog`](../../src/main/java/com/segfault03/ideadb/dialog/AlterTableDialog.java).
[View the implemented dialog in IntelliJ](final/README.md).

The images below preserve the original design study. See
[the native IntelliJ validation instructions](../../docs/UI_PREVIEW.md#column-inspector)
for the functioning dialog and its current captures.

Open [the comparison gallery](index.html) to switch between concepts and themes.
The [light](renders/overview-light.png) and [dark](renders/overview-dark.png)
comparison sheets show all three concepts side by side.
All renders are actual Swing component painting at 2× density, using JDK 21,
the IntelliJ 2025.1 test application and FlatLaf 3.7.2. FlatLaf approximates the
IDE themes; these are not live IDE window captures.

The production dialog is included as a reference. All three proposals show the
same fixture: `shop.customers.email`, with its length changed from 180 to 255.

| Concept | Layout | Best suited to | Trade-off |
| --- | --- | --- | --- |
| 01 · Column Inspector | Column navigator + property inspector | Moving between individual columns while keeping context | Less of the schema is visible at once |
| 02 · Structure Grid | Full schema grid + selected column editor | Comparing columns and reviewing changes together | More information competes for space |
| 03 · Operation Studio | Task rail + focused form + before/after card | Discoverable operations with a clear single task | Switching operations takes another navigation step |

**Suggested direction: 01.** It gives the current dialog a clearer hierarchy,
keeps the selected column visible, and borrows the connection dialog's split
layout without making the form denser. Choose 02 for a schema overview, or 03
if explicit operations are the priority.

## Scope

`AlterTableConcepts.java` is isolated from the plugin source set and build.
It uses production `DatabaseInputs`, `DatabaseUi`, `DatabaseTable` and `Icons`
for the existing controls, sections, toolbar treatment, key markers and SVGs.
The three layouts contain no action listeners or database callbacks. Inputs
are real Swing widgets; SQL, change counts and before/after text are static
fixtures. No SQL generator, change staging, validation or execution behavior
has been implemented in these archived concepts. The functioning production
inspector is separate from this presentation code.

## Re-render

From the repository root, with the JDK and IntelliJ dependencies available:

```sh
python design/alter-table/render.py --java-home /path/to/jdk-21 --gradle-user-home /path/to/gradle-cache
```

The renderer also accepts `--ide-home`, `--flatlaf-jar`, and the shared dependency
options from `scripts/ui-preview.py`. It compiles into ignored
`build/alter-table-pocs/` and writes PNGs to `design/alter-table/renders/`.
It leaves `screenshots/` untouched. On this workspace the render command was:

```sh
python design/alter-table/render.py --java-home /workspace/.jdk21 --gradle-user-home /workspace/.gradle --flatlaf-jar build/alter-table-pocs/deps/flatlaf-3.7.2.jar
```
