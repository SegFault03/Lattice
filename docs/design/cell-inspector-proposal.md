# Cell inspector proposal

Status: approved and implemented. The user removed ID from the subtitle, requested controls closer to the right edge, rounder buttons, and larger icons during implementation.

Base: `codex-cloud/ui-fixes` at `d6c0879`, pulled before design work.

These are generated design renders, not screenshots of a running IDE:

- [Light prototype](cell-inspector-proposal-light.png)
- [Dark prototype](cell-inspector-proposal-dark.png)

The proposal retains Inspect in the table toolbar. The inspector uses a fixed header row for pin and close, with a filled gray pin and neutral selected surface. The column name remains the title and the subtitle shows only row and column coordinates (for example, `Row 2 · Column 4`). A labeled value area leads into compact Edit value and Copy actions. Column metadata stays visible in aligned rows, followed by a compact Edit table action. More and the column-collapse control are removed from the proposed surface.

The selected date is in row 2, data column 4. The original generated renders retain ID in their subtitle; the explicit user correction above takes precedence. The implementation uses the same pinned date fixture for visual comparison in both themes. Native screenshots and comparison evidence are documented in [design QA](../../design-qa.md).

The filled pin asset is the unmodified JetBrains IntelliJ `toolbar/pin.svg` artwork, copyright JetBrains and contributors, under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0). It is tinted gray at runtime; all other inspector icons reuse existing plugin/platform artwork.
