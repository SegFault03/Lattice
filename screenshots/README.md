# UI screenshots

These PNGs are generated from Lattice's Swing components with sample data. The root README features `side-panel.png`, `connection-dialog.png`, `table-view.png`, `table-editing.png` and `sql-console.png`. Their filenames are stable; the remaining PNGs provide light/dark themes, welcome screens, shared input controls, schema dialogs and deletion warnings, connection test feedback, narrow layouts, native grid/console delegates and alternate editing/execution states.

Connection cases include `*-connection-mysql-bundled-expanded-600.png`, `*-connection-hsql-bundled-expanded-600.png` and the HSQLDB memory/file equivalents; `*-connection-mysql-collapsed-after-expansion-600.png` shows the restored compact form. The `*-incomplete-600.png` connection cases show disabled testing. `*-connection-driver-local-600.png` shows the separate folder button, and `*-side-panel-connection-menu-340.png` shows the first-connection database choice.

From the repository root, run:

```text
python scripts/ui-preview.py
```

Every successful run refreshes this folder, even when `--output` selects a different gallery directory. Review `build/ui-preview/index.html` and commit updated PNGs alongside UI changes. Build dependencies and compiled classes remain in ignored `build/`.

See [UI preview instructions](../docs/UI_PREVIEW.md) for JDK/SDK setup, before/after comparison, coverage and theme limitations.

`*-table-native-1100.png` and `*-sql-console-native-1100.png` use IntelliJ button/header/tab delegates. `*-table-auto-cell-editing-1100.png` and `*-table-new-auto-row-1100.png` show editable generated columns. Table hover screenshots were removed because both grids now disable hover surfaces.
