# SQL editor support

Lattice edits SQL in your own project, not only in its console. Today that means dialect-aware syntax highlighting for `.sql` files. Completion, hover details and SQL inside Java and Kotlin strings are not available yet.

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

## Choosing a connection

A file's dialect comes from, in order:

1. The connection or dialect chosen for that file.
2. The project's default connection.
3. Generic SQL.

When a `.sql` file has no choice and the project has no default, a banner offers **Choose connection**, **Use for project** and **Keep generic**. You can also use:

- **Lattice: SQL Connection for This File...** in the editor's right-click menu.
- **Lattice: SQL Connection for Project...** in the Tools menu.

Choices are stored in the project's workspace file, not in version control, because connection IDs are local to your machine. A choice that points to a deleted connection is ignored.

## Settings

**Settings | Tools | Lattice SQL Editor**

| Setting | Default |
|---|---|
| Enable Lattice SQL editor support | On. Off where IntelliJ IDEA Ultimate's Database plugin is enabled. |
| Handle `.sql` files | On |
| Syntax highlighting | On |

Colors are under **Settings | Editor | Color Scheme | Lattice SQL**.

## Turning it off

Lattice replaces the IDE's highlighting-only SQL file type for the `.sql` extension. The first time this happens in an IDE, a notice says so and offers **Turn off**. You can also:

- Clear **Handle .sql files** in the settings above. The IDE's own SQL file type is restored immediately, without a restart.
- Reassign `*.sql` yourself under **Settings | Editor | File Types**. Lattice does not override a mapping you changed by hand unless you change its setting.

## IntelliJ IDEA Ultimate

The Database plugin brings its own SQL support. Where it is enabled, Lattice's SQL editor support starts off and a one-time notice offers to turn it on. Behavior with both active has not been tested, so leave it off unless you want to try.

## Limits

- Highlighting is lexical. It does not check that tables, columns or syntax are valid.
- Word lists are curated, not exhaustive, and do not vary by server version.
- `/*! ... */` MySQL executable comments are shown as comments.
- Parentheses are not matched yet.
- The editor's structure view (PSI) is generic; only highlighting follows the dialect.
