# Cell inspector proposal

Status: design prototype awaiting user review. No UI implementation changes are included.

Base: `codex-cloud/ui-fixes` at `d6c0879`, pulled before design work.

These are generated design renders, not screenshots of a running IDE:

- [Light prototype](cell-inspector-proposal-light.png)
- [Dark prototype](cell-inspector-proposal-dark.png)

The proposal retains Inspect in the table toolbar. The inspector uses a fixed header row for pin and close, with a filled gray pin and neutral selected surface. The column name remains the title and the subtitle shows row/key/column context. A labeled value area leads into compact Edit value and Copy actions. Column metadata stays visible in aligned rows, followed by a compact Edit table action. More and the column-collapse control are removed from the proposed surface.

The selected date is in row 2, data column 4, matching the subtitle. The renders show the pinned state in both themes. Product code, interactions, and native Windows rendering remain unchanged pending review.
