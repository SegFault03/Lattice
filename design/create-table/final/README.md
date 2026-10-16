# Column Composer · implemented design

The create-table dialog now uses concept 01. These are actual desktop captures
of the production Swing dialog in IntelliJ IDEA Community 2025.1, with the
installed Lattice plugin. Dialog images are crops of the live modal window.

| Theme | Dialog | IntelliJ context |
| --- | --- | --- |
| Dark | [Open dialog](dark-dialog.png) | [Full IDE](dark-intellij.png) |
| Light | [Open dialog](light-dialog.png) | [Full IDE](light-intellij.png) |

![Column Composer in IntelliJ Dark](dark-dialog.png)

The navigator retains all column drafts while switching and filtering. Add,
remove and reorder columns; edit names, types, lengths/precision, nullability,
primary keys, identity and defaults. The complete, provider-specific CREATE
statement updates as the definition changes. Invalid hidden drafts block Create.

Creation runs in the dialog on a background connection. Controls show animated
busy feedback and block duplicate submissions. A database error retains the
whole definition for correction and retry. Success closes the dialog and
refreshes the explorer. Closing a changed definition requires a discard decision.

Validation includes 173 Java tests covering the full ordinary suite, plus a
focused sizing recheck after adjusting the SQL pane. Embedded HSQLDB/H2 tests
compare preview with executed CREATE statements and verify column order,
identity, decimal scale, working defaults and composite-key enforcement. The
Swing gallery rendered 124 states, and the focused Linux runner tests passed.

The native IntelliJ scenario passes in Dark and Light. It creates a six-column
table, verifies metadata and
inserted data through JDBC, checks invalid hidden drafts, duplicates, filtering,
reordering and cancellation, then exercises a database failure and successful
retry with the preserved form. MySQL/PostgreSQL preview/default definitions are
covered through their providers; this review does not use live servers for them.

See [native review instructions](../../../docs/UI_PREVIEW.md#column-composer).
Logs, JUnit results, runtime evidence and busy/error captures are under
`build/create-table-final/`. The original POCs remain in the
[comparison gallery](../index.html).
