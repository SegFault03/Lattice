# UI screenshots

These seven PNGs are generated from Lattice's Swing components with sample data and featured in the root README. They cover the welcome screen, the Value Workspace side panel, connection and alter-table dialogs, table viewing and editing, and the SQL console. The `ui-preview.py` helper refreshes these stable filenames on every successful run; other preview variants are available in the local gallery.

From the repository root, run:

```text
python scripts/ui-preview.py
```

This renders exactly seven light-theme screenshots. Add `--all-previews` to include light/dark versions of the welcome screen, shared input controls, schema dialogs, and alternate connection, table and console states. Every successful run keeps this folder limited to the seven README images, even when `--output` selects a different gallery directory. Review `build/ui-preview/index.html` and commit updated PNGs alongside UI changes. Build dependencies, full preview variants and compiled classes remain in ignored `build/`.

See [UI preview instructions](../docs/UI_PREVIEW.md) for JDK/SDK setup, before/after comparison, coverage and theme limitations.
