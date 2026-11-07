# Alter table: Swing concepts

Layout concepts for the alter-table dialog. See [design studies](../README.md) for the rules, how to add a study and full render options.

Open [the comparison gallery](index.html) to switch between concepts and themes. The [light](renders/overview-light.png) and [dark](renders/overview-dark.png) sheets show all three side by side. Every concept shows the same fixture: `shop.customers.email`, with its length changed from 180 to 255.

| Concept | Layout | Best suited to | Trade-off |
| --- | --- | --- | --- |
| 01 · Column Inspector | Column navigator + property inspector | Moving between individual columns while keeping context | Less of the schema is visible at once |
| 02 · Structure Grid | Full schema grid + selected column editor | Comparing columns and reviewing changes together | More information competes for space |
| 03 · Operation Studio | Task rail + focused form + before/after card | Discoverable operations with a clear single task | Switching operations takes another navigation step |

The production dialog is [`AlterTablePanel`](../../src/main/java/com/segfault03/ideadb/dialog/AlterTablePanel.java) and [`AlterTableDialog`](../../src/main/java/com/segfault03/ideadb/dialog/AlterTableDialog.java). See its [native captures](final/README.md) and [review instructions](../../docs/UI_PREVIEW.md#column-inspector).

## Re-render

From the repository root:

```sh
python design/alter-table/render.py --java-home /path/to/jdk-21 --gradle-user-home /path/to/gradle-cache
```
