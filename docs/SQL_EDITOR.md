# SQL editor support

Lattice edits SQL in your own project, not only in its console. Today that means dialect-aware syntax highlighting, code completion, hover details and local validation, in `.sql` files and in SQL strings of Java and Kotlin code.

## What you get

`.sql` files are highlighted for the database dialect of the connection you choose. The same text is colored differently where the dialects differ:

| Dialect | Differences handled |
|---|---|
| MySQL | `#` and `-- ` comments (`--` needs a following space), backtick identifiers, double-quoted strings, backslash escapes, `@variables`, `/*! ... */` |
| PostgreSQL | Double-quoted identifiers, `$$ ... $$` and `$tag$ ... $tag$` strings, `E'...'` escape strings, `::` casts, `$1` parameters, nested `/* */` comments |
| HSQLDB | Standard quoting and comments |
| H2 | `//` line comments and `$$ ... $$` strings, otherwise standard |
| Generic SQL | Standard quoting and `--` comments; used when no connection applies |

Keywords, built-in functions and data types are colored from per-dialect word lists, so `RETURNING` is a keyword for PostgreSQL but an ordinary name for MySQL. A function name is colored as a function only when a call parenthesis follows it. A data type is colored as a type and a function call as a function even where the dialect also lists the word as a keyword (`BINARY`, `LEFT(...)`), except for words that are syntax first (`SET`, `IDENTITY`, `IN (`, `EXISTS (`, `VALUES (`...). Types and functions inherit colors of their own, so they show next to keywords in any color scheme. Parameters (`?`, `:name`, `$1`, `@name`) have their own color.

## Completion

Completion works in `.sql` files, follows the file's dialect, and needs no shortcut after a dot or after a clause word that expects a table or column (`FROM`, `JOIN`, `WHERE`, `SET`, `ORDER BY`...). `Ctrl+Space` opens it anywhere else.

| Where the caret is | Suggestions |
|---|---|
| Start of a statement | `SELECT`, `INSERT INTO`, `UPDATE`, `DELETE FROM`, `CREATE TABLE`... plus the dialect's own (`SHOW` and `USE` for MySQL, `COPY` for PostgreSQL, `MERGE INTO` for H2 and HSQLDB) |
| After `FROM`, `JOIN`, `UPDATE`, `INSERT INTO`, `DROP TABLE` | Tables and views of the default schema, and the other schemas (partitioned and foreign tables count as tables, materialized views as views) |
| After `schema.` | That schema's tables |
| Select list, `WHERE`, `HAVING`, `ORDER BY`, `GROUP BY` | Columns of the tables in the statement, aliases, functions, and keywords where the word before the caret ends an expression (`IS NULL`, `ASC`, `GROUP BY`...) |
| After `alias.` or `table.` | That table's columns |
| After `JOIN`, with tables already in the statement | Every table joined to one of them by a foreign key, in either direction, first and written out with an alias and its condition: after `FROM orders o JOIN`, `customers c ON c.id = o.customer_id` and `order_items oi ON oi.order_id = o.id`. The alias is the initials of the table's words (`order_items` gives `oi`), numbered when that name is taken (`c2`) or a keyword. Plain tables follow |
| `ON` after a `JOIN` | The foreign key equality between the joined table and an earlier one, such as `o.customer_id = c.id`, first |
| `INSERT INTO t (`, `UPDATE t SET` | The table's columns, minus the ones already listed |
| After `::` or `CAST(x AS` | The dialect's data types |
| After a table | `WHERE`, joins, `GROUP BY`, `ORDER BY`, and the dialect's row limit: `LIMIT` for MySQL, PostgreSQL, H2 and HSQLDB; `FETCH FIRST` for every dialect but MySQL |

Details:

- Tables, views and columns have the explorer's icons (a view differs from a table; a primary key column shows a key and a foreign key column the foreign key mark). A column's type is prefixed with `PK` or `FK`, and the table is named after the column only where the qualifier does not already say it: after `alias.` the list shows no table.
- Tables resolve through aliases, schema-qualified names and subqueries: a subquery sees its own tables and the enclosing query's. A column that exists in several tables of the statement is inserted as `alias.column`.
- Names are quoted only when the dialect needs it: PostgreSQL folds unquoted names to lower case and H2 and HSQLDB to upper case, so a name stored in another case is inserted quoted there; MySQL keeps case as written. If you already typed an opening quote, the suggestion closes it.
- Keywords follow your case: type `sel` and get `select`, type `SEL` and get `SELECT`. Functions follow the same case and insert their parentheses.
- Nothing is suggested inside comments and string literals.
- Names come from the metadata cache. The first completion for a connection reads its default schema in the background (columns for up to 300 tables) and the popup says so; the next popup includes the result. Completion never connects on its own to a connection nobody used, and a DDL change run in the console refreshes only the affected table.
- Without a connection that applies, keywords, functions and types are still offered.

## Hover details

Hover over a name in a `.sql` file (or press `Ctrl+Q`, `F1` on macOS) to see what the file's connection knows about it. The popup's location bar names the connection and its database type (`shop-mysql · MySQL`) with the type's icon; the card itself does not repeat it.

| Over | Card |
|---|---|
| A table or view | Comment, the columns as an aligned table of name and type with the flags `PK`, `FK` and `not null`, foreign keys (each a link to its target column), the columns that reference it, and **Open in Lattice** to open its data editor |
| A column, bare or after `alias.`, `table.` or `schema.table.` | Type, comment, nullability (`nullable` or `not null`), default, primary or foreign key (the target is a link), auto increment or generated |
| An alias | The table it stands for, as a link, then the table's card: comment, columns, keys and what references it |
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
3. The saved connection that the project's own configuration files point at (see below), when exactly one does.
4. The only saved connection, when there is exactly one.
5. The database type the project's configuration files agree on, with no connection (so keywords and quoting are right, but there is no schema).
6. Generic SQL.

When a `.sql` file has no choice, the project has no default and there are two or more connections, a banner reads "Generic SQL: no connection chosen" and offers **Choose connection...** and **Use generic SQL**. You can also use:

- The **SQL: ...** entry in the status bar, which shows the dialect and connection of the current file and opens the chooser above itself when clicked, like Encoding and Line Separator. It shows for `.sql` files and for Java and Kotlin files that currently have SQL in strings; choosing there binds the Java or Kotlin file.
- **Lattice: SQL Connection for This File...** in the editor's right-click menu.
- **Lattice: SQL Connection for Project...** in the Tools menu.

The chooser lists the saved connections first, each with its database icon and type, and preselects the one in effect. After a separator come **Generic SQL**, **Follow project default (...)** (only for a file with a choice of its own; it clears that choice and names what it falls back to), **Add ...** for each database found in the project's files, and **Set project default...**, which opens the same list for the whole project.

Choices are stored in the project's workspace file, not in version control, because connection IDs are local to your machine. A file's choice follows it when it or a folder above it is renamed or moved, and is forgotten when it is deleted. When a connection is deleted, files bound to it become unbound and a default that pointed to it is cleared; an explicit generic-SQL choice is kept.

## Validation

Mistakes are underlined as you type, from the same cached schema that completion uses. Nothing is sent to the database. Each kind is its own inspection under **Settings | Editor | Inspections | Lattice SQL**, so its severity and on/off state are yours to set; **Validation** in the SQL editor settings turns all of them off.

| Inspection | Reports | Default |
|---|---|---|
| Syntax error | Unbalanced brackets; an unterminated string, quoted name or comment; a misspelled statement keyword (`selct`); clauses out of order or repeated (`WHERE` after `GROUP BY`, two `FROM`s); an empty select list; a missing table name, operand or `VALUES`/`SET`; stray commas; a missing comma between select items. | Error |
| Unknown table | A table the cached schema does not list, with a "did you mean" fix. | Warning |
| Unknown column | A column none of the query's tables has, in a select list, condition, `ORDER BY`, `SET` or `INSERT` column list, with a fix for a likely typo. | Warning |
| Ambiguous column | An unqualified column that more than one table of the query has. | Warning |
| Table alias problem | A qualifier no table or alias provides, a table used by its own name after being aliased (`orders.id` after `FROM orders o`), the same alias twice in one `FROM`. | Warning |

To silence one finding, put `-- noinspection <ShortName>` (several names separated by commas, or `ALL`) on its own line directly above the statement, with no blank line between. A comment at the top of the file, before the first statement and separated from it by a blank line, covers the whole file. The short names are `LatticeSqlSyntax`, `LatticeSqlUnknownTable`, `LatticeSqlUnknownColumn`, `LatticeSqlAmbiguousColumn` and `LatticeSqlAlias`. Alt+Enter on a finding offers **Suppress for statement** and **Suppress for file**, which write the comment or add the name to the one already there. In SQL inside Java and Kotlin strings, suppress through the host language instead.

It is built to be quiet when unsure:

- **Needs the schema, or says nothing.** Table and column checks run only when a connection applies and its schema is cached. They are suspended while a change to the connection may have made the cache out of date (every statement run in a console reports one); the schema is then read again in the background and the checks return. Completion and hover start the first read for a connection; once a connection has been read, a change that marks its schema stale is read again in the background when a file is next checked.
- **Only names it can be sure of.** A column is judged only against a table whose columns could be read; a table whose columns could not be read is skipped. An unqualified column is judged only when every table the query can see is a real table with its columns read. A derived table, a CTE, a function that returns rows, a table the file creates, renames or alters, a `USING` or `NATURAL` join, `UPDATE` with several tables or `DELETE ... USING` make the query's unqualified names unjudged. Select list aliases, the SQL standard's words (`PRECEDING`, `ZONE`, `EPOCH`...), `ON CONFLICT`, `ON DUPLICATE KEY` and `RETURNING` clauses, window definitions and index hints are never called columns. The pseudo columns an engine adds to its tables (PostgreSQL `ctid` and `xmin`, H2 `_ROWID_`) are accepted; a generic dialect accepts every engine's.
- **Scripts that are not plain statement lists.** A statement with `${...}` or `{{...}}` is skipped. After `DELIMITER`, a stored routine with a `BEGIN` body, `DO` or `DECLARE`, the rest of the file is skipped because one statement then spans several semicolons. After `USE`, `SET SCHEMA` or `SET search_path`, unqualified table names are not judged.
- **Strings in code.** SQL in a Java or Kotlin string is validated like a file, except when part of it is only known at run time (`" + table + "`, `$id`), or when it contains an escape such as `\n`: then it is not judged at all.
- **Unfinished statements.** The last statement of a script file with no `;` after it may still be being typed, so a missing final piece is not reported as incomplete. SQL in a code string is complete by itself and is checked as such.
- **Large files.** A script over 200,000 characters is not validated.
- **Names** match ignoring case and quoting, so a difference only in case is never reported.

## Connections found in the project

Lattice reads the database URLs in the project's configuration files, so a Spring or Jakarta EE project needs no setup:

| Files | Keys read |
|---|---|
| `application*.properties`, `.yml`, `.yaml`, `bootstrap*` | any key ending in `url`, `jdbc-url` or `connection-url`: `spring.datasource.url`, `quarkus.datasource.jdbc.url`, `datasources.default.url` |
| `persistence.xml` | `jakarta.persistence.jdbc.url`, `javax.persistence.jdbc.url`, `hibernate.connection.url` |
| `hibernate.cfg.xml`, `hibernate.properties`, `context.xml` (Tomcat `Resource`), `jdbc*.properties`, `database*.properties`, `db*.properties` | URL properties and attributes |

Only values that start with `jdbc:` for MySQL (and MariaDB), PostgreSQL, H2 or HSQLDB count. Spring placeholders resolve to their default (`${DB_HOST:localhost}`) or to another setting of the same file; the environment is not consulted, and a part that stays unknown matches any saved connection. `.properties` escapes (`jdbc\:mysql\://host`, as `Properties.store` writes them), continuation lines and `!` comments are read as Java reads them. Build output (`build`, `target`, `out`) is skipped. Each `---` document of a YAML file is read on its own, so profiles are all found.

What this does:

- **A saved connection that a URL points at applies by itself** (same type, server, port and database; `localhost` and `127.0.0.1` are the same). When the files point at two different saved connections, such as dev and test, none is guessed; the chooser lists them.
- **A database that is not saved yet is offered.** A banner on the SQL file reads "application.properties: PostgreSQL billing on db.internal" (the port only when it is not the default; the full description is in the tooltip) with **Add connection...**, which opens the connection dialog with the type, server, port, database and user name filled in. **Choose...** (when connections are saved) opens the chooser, and **Not now** turns that offer down for the project. **Tools | Lattice: Add Connection from Project Configuration...** and the file's connection chooser offer the same. Saving it also makes it the project default if there is none.
- **Passwords are never read.** Passwords, tokens and other secrets in these files are skipped as the file is read, and URL parameters other than `user` (`?password=...`, `;PASSWORD=...`, `user:pass@host`) are dropped when the URL is parsed. The one credential kept is the user name: the setting beside the URL (`spring.datasource.username` or `.user`, `jakarta.persistence.jdbc.user`, `javax.persistence.jdbc.user`, `hibernate.connection.username`, `quarkus.datasource.username`, a Tomcat `Resource`'s `username`) or, failing that, a `user=` parameter of the URL. It pre-fills the new connection's user; the password is empty for you to enter.

The status bar entry says when a connection was chosen because of the project's files. Files are read in the background when the project opens and again when one of them changes.

## Settings

**Settings | Tools | Lattice SQL Editor** (and its **Project Defaults** page)

| Setting | Default |
|---|---|
| Enable Lattice SQL editor support | On. Off where IntelliJ IDEA Ultimate's Database plugin is enabled. |
| Handle `.sql` files | On |
| Syntax highlighting | On |
| Code completion | On |
| Keyword case when inserted | As typed (or always upper or lower case) |
| Hover documentation | On |
| Validation | On, as five inspections whose severities you set under Editor | Inspections; **Configure inspections...** opens that page at Lattice SQL |
| SQL in Java and Kotlin strings | On, as three switches: marked strings, known calls and strict content check |
| Known calls | A list of JDBC, Spring `JdbcTemplate`, JPA, Hibernate, jOOQ and Exposed method names, edited with the add, edit and remove buttons; an empty list means the default one |

Everything on the page except the first checkbox is disabled while Lattice SQL editor support is off.

The **Project Defaults** page sets what applies to the current project only: the default connection for its SQL (or Automatic, which uses the project's files and then the only connection), whether its configuration files are read to find the database, and, while some offers are turned down, a checkbox "Offer the N databases I turned down again" that takes effect when you apply the page.

Colors are under **Settings | Editor | Color Scheme | Lattice SQL**. **Tools | Lattice: Restore the IDE's Built-in SQL Handling** switches off `.sql` handling in one step, with an undo in the notice it shows.

## Large files and projects

- In a file larger than 100 KB, completion and hover analyze the statement around the caret (up to the surrounding `;` at a line end, within about 50 KB each way), so a database dump stays as responsive as a small script. Highlighting is incremental and not affected.
- Completion and hover read the schema cache and never wait for the database. For a schema of 5,000 tables with 40 columns each, building the suggestions takes a few milliseconds; only the default schema is read ahead, with columns for up to 300 tables.
- Work is cancelled when you keep typing. While the IDE is indexing, highlighting, completion, hover and SQL in strings keep working; only what needs the indexes is skipped: `@Language` on a resolved method's parameters and the search for configuration files, which run after indexing.

## Turning it off

Lattice replaces the IDE's highlighting-only SQL file type for the `.sql` extension. The first time this happens in an IDE, a notice says so and offers **Turn off**. You can also:

- Clear **Handle .sql files** in the settings above. The IDE's own SQL file type is restored immediately, without a restart.
- Reassign `*.sql` yourself under **Settings | Editor | File Types**. Lattice does not override a mapping you changed by hand unless you change its setting.

## IDE support

Lattice's SQL editor needs IntelliJ IDEA 2025.1 or later (build 251+). SQL in Java and Kotlin strings needs the bundled Java and Kotlin plugins, which Lattice loads only when present; the Kotlin part reads Kotlin PSI only and works in both the K1 and K2 modes. Hover uses the platform's documentation target API, which JetBrains marks experimental: Plugin Verifier reports those uses and a future IDE release may require a small adaptation. See [compatibility](compatibility.md).

## IntelliJ IDEA Ultimate

The Database plugin brings its own SQL support. Where it is enabled, Lattice's SQL editor support starts off and a one-time notice offers to turn it on. Behavior with both active has not been tested, so leave it off unless you want to try.

## Limits

- Not available yet: filling in parameters (`?`, `:name`, `$1`) to preview the final SQL, sending a statement from the editor to the Lattice console, and a read-only dry run.
- Strings are found by what is written at the call site. Kotlin calls are matched by name only, because Kotlin PSI cannot resolve a call here; Java also honors `@Language` on a resolved method's parameters.
- R2DBC URLs, other databases and URLs assembled in code are not read, and a URL whose parts are all unresolved placeholders matches by type only.
- Validation is a tolerant scanner, not a full SQL parser: it finds the mistakes above, not every way a statement can be invalid for a particular server, and it can miss a mistake that sits behind something it cannot see through.
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
