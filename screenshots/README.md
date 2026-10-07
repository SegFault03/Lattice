# UI screenshots

These PNGs are generated from Lattice's Swing components with sample data. The root README features `side-panel.png`, `connection-dialog.png`, `table-view.png`, `table-editing.png` and `sql-console.png`. Their filenames are stable; the remaining PNGs provide light/dark themes and alternate UI states.

From the repository root, run:

```text
python scripts/ui-preview.py
```

Every successful run refreshes this folder, even when `--output` selects a different gallery directory. Review `build/ui-preview/index.html` and commit updated PNGs alongside UI changes. Build dependencies and compiled classes remain in ignored `build/`.

See [UI preview instructions](../docs/UI_PREVIEW.md) for JDK/SDK setup, before/after comparison, coverage and theme limitations.
