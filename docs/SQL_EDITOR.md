# SQL editor support

Lattice edits SQL in your own project, not only in its console. Today that means dialect-aware syntax highlighting and code completion for `.sql` files. Hover details and SQL inside Java and Kotlin strings are not available yet.

## What you get

`.sql` files are highlighted for the database dialect of the connection you choose. The same text is colored differently where the dialects differ:

| Dialect | Differences handled |
|---|---|
| MySQL | `#` and `-- ` comments (`--` needs a following space), backtick identifiers, double-quoted strings, backslash escapes, `@variables`, `/*! ... */` |
| PostgreSQL | Double-quoted identifiers, `$$ ... $$` and `$tag$ ... $tag$` strings, `E'...'` escape strings, `::` casts, `$1` parameters, nested `/* */` comments |
| HSQLDB | Standard quoting and comments |
| H2 | `//` line comments and `$$ ... $$` strings, otherwise standard |
| Generic SQL | Standard quoting and `--` comments; used when no connection applies |

Keywords, built-in functions and data types are colored from per-dialect word lists, so `RETURNING` is a keyword for PostgreSQL but an ordinary name for MySQL. A function name is colored as a function only when a call parenthesis follows it. Parameters (`?`, `:name`, `$1`, `@name`) have their own color.

## Completion

Completion works in `.sql` files, follows the file's dialect, and needs no shortcut after a dot or after a clause word that expects a table or column (`FROM`, `JOIN`, `WHERE`, `SET`, `ORDER BY`...). `Ctrl+Space` opens it anywhere else.

| Where the caret is | Suggestions |
|---|---|
| Start of a statement | `SELECT`, `INSERT INTO`, `UPDATE`, `DELETE FROM`, `CREATE TABLE`... plus the dialect's own (`SHOW` and `USE` for MySQL, `COPY` for PostgreSQL, `MERGE INTO` for H2 and HSQLDB) |
| After `FROM`, `JOIN`, `UPDATE`, `INSERT INTO`, `DROP TABLE` | Tables and views of the default schema, and the other schemas |
| After `schema.` | That schema's tables |
| Select list, `WHERE`, `HAVING`, `ORDER BY`, `GROUP BY` | Columns of the tables in the statement, aliases, functions, and keywords where the word before the caret ends an expression (`IS NULL`, `ASC`, `GROUP BY`...) |
| After `alias.` or `table.` | That table's columns |
| `ON` after a `JOIN` | The foreign key equality between the joined table and an earlier one, such as `o.customer_id = c.id`, first |
| `INSERT INTO t (`, `UPDATE t SET` | The table's columns, minus the ones already listed |
| After `::` or `CAST(x AS` | The dialect's data types |
| After a table | `WHERE`, joins, `GROUP BY`, `ORDER BY`, and the dialect's row limit: `LIMIT` for MySQL, PostgreSQL, H2 and HSQLDB; `FETCH FIRST` for every dialect but MySQL |

Details:

- Tables resolve through aliases, schema-qualified names and subqueries: a subquery sees its own tables and the enclosing query's. A column that exists in several tables of the statement is inserted as `alias.column`.
- Names are quoted only when the dialect needs it: PostgreSQL folds unquoted names to lower case and H2 and HSQLDB to upper case, so a name stored in another case is inserted quoted there; MySQL keeps case as written. If you already typed an opening quote, the suggestion closes it.
- Keywords follow your case: type `sel` and get `select`, type `SEL` and get `SELECT`. Functions follow the same case and insert their parentheses.
- Nothing is suggested inside comments and string literals.
- Names come from the metadata cache. The first completion for a connection reads its default schema in the background (columns for up to 300 tables) and the popup says so; the next popup includes the result. Completion never connects on its own to a connection nobody used, and a DDL change run in the console refreshes only the affected table.
- Without a connection that applies, keywords, functions and types are still offered.

## Choosing a connection

A file's dialect comes from, in order:

1. The connection or dialect chosen for that file.
2. The project's default connection.
3. The only saved connection, when there is exactly one.
4. Generic SQL.

When a `.sql` file has no choice, the project has no default and there are two or more connections, a banner offers **Choose connection**, **Use for project** and **Keep generic**. You can also use:

- The **SQL: ...** entry in the status bar, which shows the dialect and connection of the current `.sql` file and opens the same chooser when clicked.
- **Lattice: SQL Connection for This File...** in the editor's right-click menu.
- **Lattice: SQL Connection for Project...** in the Tools menu.

Choices are stored in the project's workspace file, not in version control, because connection IDs are local to your machine. When a connection is deleted, files bound to it become unbound and a default that pointed to it is cleared; an explicit generic-SQL choice is kept.

## Settings

**Settings | Tools | Lattice SQL Editor**

| Setting | Default |
|---|---|
| Enable Lattice SQL editor support | On. Off where IntelliJ IDEA Ultimate's Database plugin is enabled. |
| Handle `.sql` files | On |
| Syntax highlighting | On |
| Code completion | On |

Colors are under **Settings | Editor | Color Scheme | Lattice SQL**.

## Turning it off

Lattice replaces the IDE's highlighting-only SQL file type for the `.sql` extension. The first time this happens in an IDE, a notice says so and offers **Turn off**. You can also:

- Clear **Handle .sql files** in the settings above. The IDE's own SQL file type is restored immediately, without a restart.
- Reassign `*.sql` yourself under **Settings | Editor | File Types**. Lattice does not override a mapping you changed by hand unless you change its setting.

## IntelliJ IDEA Ultimate

The Database plugin brings its own SQL support. Where it is enabled, Lattice's SQL editor support starts off and a one-time notice offers to turn it on. Behavior with both active has not been tested, so leave it off unless you want to try.

## Limits

- Completion does not offer the columns of a derived table or common table expression, and suggests a composite foreign key as one equality per column.
- Only the default schema is read ahead of time; another schema's tables appear after it is read (for example by opening it in the explorer).
- Highlighting is lexical. It does not check that tables, columns or syntax are valid.
- Word lists are curated, not exhaustive, and do not vary by server version.
- `/*! ... */` MySQL executable comments are shown as comments.
- Parentheses are not matched yet.
- The editor's structure view (PSI) is generic; only highlighting follows the dialect.
