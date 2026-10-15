# Column Inspector · implemented design

These are desktop captures of the production Swing dialog running in IntelliJ
IDEA Community 2025.1 with the installed Lattice plugin. The dialog images are
crops of the live modal window. No replacement look and feel is used.

| Theme | Dialog | IntelliJ context |
| --- | --- | --- |
| Dark | [Open dialog](dark-dialog.png) | [Full IDE](dark-intellij.png) |
| Light | [Open dialog](light-dialog.png) | [Full IDE](light-intellij.png) |

![Column Inspector in IntelliJ Dark](dark-dialog.png)

## Functionality

- Filter and select columns; inspect type, length/precision, nullability and defaults.
- Add, modify, rename or drop a column; rename the table.
- Review database-specific SQL for all staged changes before applying the batch.
- Keep drafts across navigation, revert individual columns, validate the whole batch and confirm destructive actions.
- Prepare SQL and run database operations in the background with busy feedback.
- Retain remaining drafts after a partial failure without replaying successful steps; refresh the explorer after success.

## Validation

162 Java tests pass. Native IntelliJ scenarios pass in Dark and Light; the Swing
preview suite rendered 124 states successfully. Plugin packaging and archive
validation also pass.

The native IntelliJ scenario applies a batch that modifies and renames the same
column, changes another type, adds two columns, drops one and renames the table.
A second batch fails after earlier steps succeed and verifies that retry applies
only the retained remainder. JDBC metadata and retained data are checked.

Java coverage includes persistent drafts, hidden validation errors, stale whole
batch previews, final-name collisions, rename chains, drop/restore/revert,
embedded HSQLDB/H2 execution, partial failure and PostgreSQL transaction rollback.
MySQL and PostgreSQL have SQL contract coverage; this review does not run live
servers for those engines.

PostgreSQL batches run in one transaction. Other supported engines may commit DDL
incrementally. The dialog reloads actual metadata and retains remaining edits
when a batch stops. Rename cycles need an intermediate name in a separate Apply.

See [UI preview instructions](../../../docs/UI_PREVIEW.md#column-inspector) to
repeat the native review. Build output under `build/alter-table-batch/` contains
test logs, theme-specific runtime evidence, JUnit results, busy/error captures
and the complete Swing gallery.
