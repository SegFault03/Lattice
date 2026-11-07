package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** What is known about the whole script before its statements are checked one by one. */
final class SqlScriptFacts {
    private static final Set<String> TABLE_NAME_MODIFIERS = Set.of("IF", "NOT", "EXISTS", "ONLY");
    private static final Set<String> TRANSACTION_WORDS = Set.of("TRANSACTION", "WORK", "ISOLATION", "READ", "DEFERRED", "IMMEDIATE", "EXCLUSIVE", "ATOMIC");
    private static final Set<String> ROUTINE_WORDS = Set.of("FUNCTION", "PROCEDURE", "TRIGGER", "EVENT", "PACKAGE");

    final CharSequence text;
    final SqlDialectProfile profile;
    final @Nullable SqlSchema schema;
    /** Tables and views the script creates, and tables it alters: their shape is the script's, not the schema's. */
    final Set<String> created = new HashSet<>();
    final Set<String> altered = new HashSet<>();
    /** A USE, SET SCHEMA or SET search_path changes what an unqualified table means. */
    boolean changesDefaultSchema;
    /** "Did you mean" answers by the thing searched (a namespace or a table) and the lowercase name, null when nothing was close. */
    final Map<Object, Map<String, String>> suggestions = new IdentityHashMap<>();

    SqlScriptFacts(CharSequence text, SqlDialectProfile profile, @Nullable SqlSchema schema, List<List<SqlToken>> statements) {
        this.text = text; this.profile = profile; this.schema = schema;
        for (List<SqlToken> statement : statements) collect(statement);
    }

    String up(SqlToken token) { return token.text(text).toUpperCase(Locale.ROOT); }

    private void collect(List<SqlToken> s) {
        if (s.isEmpty()) return;
        String first = up(s.get(0));
        if (first.equals("USE")) changesDefaultSchema = true;
        if (first.equals("SET") && s.size() > 1) {
            String second = up(s.get(1));
            if (second.equals("SCHEMA") || second.equals("SEARCH_PATH") || second.equals("SESSION") || second.equals("LOCAL")
                    || second.equals("CATALOG")) changesDefaultSchema = true;
        }
        for (int i = 0; i < s.size(); i++) {
            String word = up(s.get(i));
            if ((word.equals("TABLE") || word.equals("VIEW")) && i + 1 < s.size() && (first.equals("CREATE") || first.equals("ALTER"))) {
                int at = i + 1;
                while (at < s.size() && TABLE_NAME_MODIFIERS.contains(up(s.get(at)))) at++;
                String name = lastNamePart(s, at);
                if (name != null) (first.equals("CREATE") ? created : altered).add(name.toLowerCase(Locale.ROOT));
            }
            // ALTER TABLE a RENAME TO b and RENAME TABLE a TO b: b exists from here on
            if (word.equals("TO") && (first.equals("RENAME") || first.equals("ALTER")) && i + 1 < s.size()) {
                String name = lastNamePart(s, i + 1);
                if (name != null) created.add(name.toLowerCase(Locale.ROOT));
            }
            // SELECT ... INTO new_table, and CREATE TABLE ... AS
            if (word.equals("INTO") && first.equals("SELECT") && i + 1 < s.size()) {
                String name = lastNamePart(s, i + 1);
                if (name != null) created.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The last part of the dotted name starting at {@code at}, unquoted. */
    @Nullable String lastNamePart(List<SqlToken> s, int at) {
        String name = null;
        while (at < s.size()) {
            SqlToken token = s.get(at);
            if (token.type() != LatticeSqlTokens.IDENTIFIER && token.type() != LatticeSqlTokens.QUOTED_IDENTIFIER && token.type() != LatticeSqlTokens.KEYWORD) break;
            name = unquote(token);
            if (at + 2 < s.size() && s.get(at + 1).type() == LatticeSqlTokens.PUNCTUATION && text.charAt(s.get(at + 1).start()) == '.') at += 2;
            else break;
        }
        return name;
    }

    String unquote(SqlToken token) {
        String raw = token.text(text);
        if (token.type() != LatticeSqlTokens.QUOTED_IDENTIFIER || raw.length() < 2) return raw;
        char quote = raw.charAt(0);
        String inner = raw.substring(1, raw.charAt(raw.length() - 1) == quote ? raw.length() - 1 : raw.length());
        return inner.replace("" + quote + quote, "" + quote);
    }

    /** Stored routines and client commands: everything from here on is not a sequence of ordinary statements. */
    boolean opaque(List<SqlToken> s) {
        String first = up(s.get(0));
        if (first.equals("DELIMITER") || first.equals("DECLARE") || first.equals("DO")) return true;
        if (first.equals("BEGIN")) return s.size() > 1 && !TRANSACTION_WORDS.contains(up(s.get(1))) || s.size() > 1 && up(s.get(1)).equals("ATOMIC");
        if (!first.equals("CREATE")) return false;
        boolean routine = false;
        for (SqlToken token : s) {
            String word = up(token);
            if (ROUTINE_WORDS.contains(word)) routine = true;
            if (routine && (word.equals("BEGIN") || word.equals("DECLARE"))) return true;
        }
        return false;
    }

    /** Template syntax (Flyway placeholders, Jinja, ERB) makes the statement something other than SQL text. */
    boolean templated(List<SqlToken> s) {
        String raw = text.subSequence(s.get(0).start(), s.get(s.size() - 1).end()).toString();
        return raw.contains("${") || raw.contains("{{") || raw.contains("{%") || raw.contains("<%") || raw.contains("#{");
    }
}
