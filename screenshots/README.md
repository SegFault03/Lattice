# UI screenshots

These five PNGs are generated from Lattice's Swing components with sample data and featured in the root README: `side-panel.png`, `connection-dialog.png`, `table-view.png`, `table-editing.png` and `sql-console.png`. The `ui-preview.py` helper refreshes these stable filenames on every successful run. Other preview variants are available in the local gallery but are not stored here.

From the repository root, run:

```text
python scripts/ui-preview.py
```

This renders exactly five light-theme screenshots. Add `--all-previews` to include light/dark versions of the welcome screen, shared input controls, schema dialogs, and alternate connection, table and console states. Every successful run keeps this folder limited to the five README images, even when `--output` selects a different gallery directory. Review `build/ui-preview/index.html` and commit updated PNGs alongside UI changes. Build dependencies, full preview variants and compiled classes remain in ignored `build/`.

See [UI preview instructions](../docs/UI_PREVIEW.md) for JDK/SDK setup, before/after comparison, coverage and theme limitations.
