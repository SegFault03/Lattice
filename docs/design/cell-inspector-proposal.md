# Cell inspector design reference

These concept renders show a design direction for the integrated cell and column
inspector. They are generated design renders, not screenshots of a running IDE:

- [Light prototype](cell-inspector-proposal-light.png)
- [Dark prototype](cell-inspector-proposal-dark.png)

The implemented inspector retains Inspect in the table toolbar. It uses a fixed
header row for pin and close, with a filled gray pin and neutral selected
surface. The column name is the title; the subtitle shows row and column
coordinates (for example, `Row 2, Column 4`). The value area leads into Edit
value and Copy actions. Column metadata stays visible in aligned rows, followed
by Edit table. More and the column-collapse control are not part of the current
surface.

The render fixture selects the date in row 2, data column 4. The original concept
images retain an ID in their subtitle; the production implementation follows
the row/column-only copy above. See [UI preview instructions](../UI_PREVIEW.md)
for current behavior and review steps.

The filled pin asset is the unmodified JetBrains IntelliJ `toolbar/pin.svg`
artwork, copyright JetBrains and contributors, under
[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0). It is tinted gray at
runtime; the other inspector icons reuse existing plugin/platform artwork.
