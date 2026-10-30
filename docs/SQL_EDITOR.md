# SQL editor support

Lattice edits SQL in your own project, not only in its console. Today that means dialect-aware syntax highlighting, code completion and hover details, in `.sql` files and in SQL strings of Java and Kotlin code.

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

## Hover details

Hover over a name in a `.sql` file (or press `Ctrl+Q`, `F1` on macOS) to see what the file's connection knows about it. The card names the connection and dialect it came from.

| Over | Card |
|---|---|
| A table or view | Comment, every column with type and `PK`/`FK`/`not null`, primary key, foreign keys (each a link to its target column), the columns that reference it, and **Open in Lattice** to open its data editor |
| A column, bare or after `alias.`, `table.` or `schema.table.` | Type, comment, nullability, default, primary or foreign key (the target is a link), auto increment or generated |
| An alias | The table it stands for, as a link |
| A schema | Its tables, as links |

Links in a card open the card of the target. Names resolve the way completion resolves them: through aliases, schema-qualified names, `UPDATE ... SET` and `INSERT` column lists, ignoring case, and with quoted names unquoted. Keywords, unknown names and derived-table aliases show nothing, as does a file with no connection or a connection whose schema has not been read yet; hovering starts that read, so the card appears on the next hover. The cards read the metadata cache and never contact the database.

## SQL in Java and Kotlin strings

A string that holds SQL gets the same highlighting, completion and hover as a `.sql` file, for the dialect and schema of the connection that applies to the file. A string is treated as SQL when any of these holds, each of which can be switched off in the settings:

| Way | What counts |
|---|---|
| Marked by you | `@Language("SQL")` (or `MySQL`, `PostgreSQL`, `H2`, `HSQLDB`) on a variable, field, parameter or method, or a `// language=SQL` comment above the statement. |
| Passed to a known call | The first argument of a method in the list under the settings: JDBC (`prepareStatement`, `executeQuery`...), Spring `JdbcTemplate` (`query`, `queryForObject`, `update`, `batchUpdate`...), JPA and Hibernate (`createNativeQuery`, `createSQLQuery`), jOOQ and Exposed (`resultQuery`, `fetch`, `exec`), and the `value` of a Spring Data `@Query(nativeQuery = true)`, `@NativeQuery` or `@NamedNativeQuery`. Names that are common outside SQL (`query`, `update`, `execute`, `fetch`, `exec`, `addBatch`) only count when the string begins like a statement. The list is editable. |
| Looks like a statement | A strict check of the text itself: `select ... from ...` with a second sign it is not prose (a `where`, a placeholder, a join, `*`, several columns...), `insert into ... values`, `update ... set x =`, `delete from ... where`, `with ... as (select`, and `create`/`alter`/`drop table`. "Select an item from the list" is left alone. |

Strings built from pieces work: Java `+` chains and text blocks, Kotlin `+` chains, raw strings (also with `trimIndent()` and `trimMargin()`) and templates. Whatever is only known at run time (`" + table + "`, `$id`, `${expr}`) is shown to the SQL support as a `?`, so `select * from ? where id = ?` still completes the columns of aliases you wrote in the literal parts.

To settle a string yourself, press `Alt+Enter` inside it:

- **Treat this string as SQL** adds `// language=SQL` above the statement.
- **Do not treat this string as SQL** adds `// lattice:not-sql` above the statement. Delete the comment to undo.

The connection comes from the Java or Kotlin file: its own choice (right-click in the string, **Lattice: SQL Connection for This File...**), then the project default, then the only saved connection.

`@Language("SQL")` and `// language=SQL` are also honored by the IDE's own language injection, which this setting does not control; manage those under **Settings | Editor | Language Injections**. The not-SQL comment applies to Lattice's own detection only.

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
| Hover documentation | On |
| SQL inside Java and Kotlin strings | On, as three switches: marked strings, known calls and strict content check |
| Known calls | JDBC, Spring `JdbcTemplate`, JPA, Hibernate, jOOQ and Exposed method names, one per line |

Colors are under **Settings | Editor | Color Scheme | Lattice SQL**.

## Turning it off

Lattice replaces the IDE's highlighting-only SQL file type for the `.sql` extension. The first time this happens in an IDE, a notice says so and offers **Turn off**. You can also:

- Clear **Handle .sql files** in the settings above. The IDE's own SQL file type is restored immediately, without a restart.
- Reassign `*.sql` yourself under **Settings | Editor | File Types**. Lattice does not override a mapping you changed by hand unless you change its setting.

## IntelliJ IDEA Ultimate

The Database plugin brings its own SQL support. Where it is enabled, Lattice's SQL editor support starts off and a one-time notice offers to turn it on. Behavior with both active has not been tested, so leave it off unless you want to try.

## Limits

- Strings are found by what is written at the call site. Kotlin calls are matched by name only, because Kotlin PSI cannot resolve a call here; Java also honors `@Language` on a resolved method's parameters.
- A `StringBuilder` chain (`sb.append("select ").append(x)`) is not followed.
- An escape such as `\n` inside a Kotlin string appears in the SQL text as written, so a keyword right after it is not recognized by the highlighter or completion.
- The heuristic is strict on purpose: `select 1`, bare `delete from t` and similar are found only through a call, annotation or comment.

- Completion does not offer the columns of a derived table or common table expression, and suggests a composite foreign key as one equality per column.
- Hover does not describe columns of derived tables or common table expressions, and an unqualified column that exists in several tables of the statement shows the first one.
- Only the default schema is read ahead of time; another schema's tables appear after it is read (for example by opening it in the explorer).
- Highlighting is lexical. It does not check that tables, columns or syntax are valid.
- Word lists are curated, not exhaustive, and do not vary by server version.
- `/*! ... */` MySQL executable comments are shown as comments.
- Parentheses are not matched yet.
- The editor's structure view (PSI) is generic; only highlighting follows the dialect.
