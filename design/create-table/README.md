# Create table: Swing concepts

Layout concepts for the create-table dialog. See [design studies](../README.md) for the rules, how to add a study and full render options.

Open [the comparison gallery](index.html) to switch between concepts and themes. The [light](renders/overview-light.png) and [dark](renders/overview-dark.png) sheets show all three side by side. Every concept shows the same six-column `shop.customers` fixture, with `email` selected.

| Concept | Layout | Best for | Trade-off |
| --- | --- | --- | --- |
| 01 · Column Composer | Column navigator and property inspector, with SQL below | Consistency with the alter-table dialog | Less direct comparison between columns |
| 02 · Schema Canvas | Full schema grid, selected-column editor and SQL | Defining and comparing many columns | Some fields appear in both the grid and editor |
| 03 · Blueprint | Ordered column cards beside a persistent SQL sheet | Reviewing the complete table while editing in place | More scrolling as expanded definitions grow |

The production dialog is [`CreateTablePanel`](../../src/main/java/com/segfault03/ideadb/dialog/CreateTablePanel.java) and [`CreateTableDialog`](../../src/main/java/com/segfault03/ideadb/dialog/CreateTableDialog.java). See its [native captures](final/README.md) and [review instructions](../../docs/UI_PREVIEW.md#column-composer).

## Re-render

From the repository root:

```sh
python design/create-table/render.py --java-home /path/to/jdk-21 --gradle-user-home /path/to/gradle-cache
```
