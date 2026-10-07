# UI copy and feedback review

This review covers the welcome editor, explorer toolbar/tree/context menus, connection and schema dialogs, table editor, SQL console and operation confirmations. The [preview helper](UI_PREVIEW.md) renders the production components in both themes without an IDE or database.

| Area | Issue found | Refresh |
|---|---|---|
| Welcome | Long tips clipped in a 520-pixel editor; a fixed card caused horizontal scrolling. | Constrained reading width, wrapping descriptions, grouped setup steps and a local HSQLDB hint. |
| Welcome actions | Open SQL Console opened a connection dialog when no connection existed and silently used the first saved connection. | Disabled until a connection exists; offers a connection menu when several are saved. Availability follows settings changes. |
| Startup preference | Negative checkbox wording required interpreting the inverse setting. | Positive “Show welcome screen on startup”; points to the explorer Help button for reopening. |
| Explorer | “Connect / Reload” and “Modify / Alter Table” combined different terms; console icons varied. | Contextual Connect / Refresh connection, Open data editor, Alter table; shared console icon. Column tooltips explain primary keys, NULL and auto-increment. Failed loads have an error glyph and diagnostic tooltip, rather than a loading indicator. |
| Connection | New MySQL/HSQLDB configurations could be titled Edit; success produced a second modal; failure said “Failed!”. | Explicit new/edit mode with Add connection / Save buttons; inline success with product/version and driver/latency tooltip; descriptive failure with error details. |
| Schema dialogs | Labels used inconsistent casing; immediate actions ended in “Now”; an OK footer suggested another apply step. | Sentence case, specific Create buttons, concise action labels; alter-table footer only Close. |
| Table feedback | Emoji warnings, missing Commit tooltip after validation, noisy row counts and color-dependent feedback. | Shared severity icons and short status text, persistent Commit description, actionable validation and auto-refresh cues. |
| SQL console | Pause and garbage-collection glyphs represented Stop and Clear; long failures filled the footer; old results looked current after failure. | Stop square and eraser icons with labels; short error status and full Messages details; Previous results caption while running and after failure. Result-limit feedback uses warning severity. |
| Deletion | Generic Yes/No questions omitted some consequences; dropping a column had a raw red button. | Explicit action / Cancel labels; scope and permanence in the confirmation; inline drop-column warning with a native warning glyph. Removing connection settings explains that database data is kept. |

## Conventions for future changes

- Use sentence case for UI labels and action text. Keep product names and SQL tokens such as NULL, WHERE and ORDER BY in their established case.
- Use a verb that describes the result: Add connection, Commit, Revert, Drop table. An ellipsis indicates another dialog or choice; an immediate action does not need one.
- Keep concise status text near the affected controls. Explain the next step for a blocked action. Put detailed diagnostics in tooltips, Messages or the native error dialog.
- Use `DatabaseUi.status` for normal, busy, success, warning and error feedback. Text and icons carry meaning alongside theme-aware color. Clear the old severity when an operation recovers.
- Reserve destructive confirmations for data removal. Name the affected object, what is removed and what is kept. Use `DatabaseUi.confirmDestructive` for an explicit action and Cancel.
- Use shared `Icons.CONSOLE`, `Icons.STOP` and `Icons.CLEAR` rather than unrelated execution, pause or garbage-collection glyphs. Custom Stop/Clear icons include dark variants.

## Review evidence and remaining IDE checks

The gallery contains first-use/configured welcome states, narrow welcome layouts, new/edit connection labels, connection test feedback, a drop-column warning, table validation and console error states. Fixture checks cover startup preference persistence, changing console availability, severity reset and existing table/console interactions.

The preview cannot validate native confirmation placement, custom IDE themes, screen-reader announcements, display scaling or live connection diagnostics. Check those in IDEA before release. SQL and database terminology is retained where it describes the actual operation; column tooltips explain compact PK/AI metadata. The five README screenshot filenames remain unchanged.

Example renders: [welcome](../screenshots/light-welcome-520.png), [dark welcome](../screenshots/dark-welcome-900.png), [connection feedback](../screenshots/light-connection-success-600.png), [column deletion](../screenshots/dark-schema-drop-column-800.png), [console failure](../screenshots/light-sql-console-messages-1100.png).
