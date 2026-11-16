package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * MySQL's {@code USE db}: the statements after it resolve unqualified names in {@code db}. Other dialects change the
 * default schema with SET statements, which are left alone.
 */
public final class SqlUseStatements {
    private SqlUseStatements() {}

    /** The database a statement's code tokens select, when the statement is a MySQL USE; otherwise null. */
    public static @Nullable String target(@NotNull List<SqlToken> code, @NotNull CharSequence text, @NotNull SqlDialectProfile profile) {
        if (profile.databaseType() != DatabaseType.MYSQL || code.size() != 2) return null;
        if (!code.get(0).text(text).toUpperCase(Locale.ROOT).equals("USE")) return null;
        SqlToken name = code.get(1);
        if (name.type() == LatticeSqlTokens.IDENTIFIER) return name.text(text);
        if (name.type() != LatticeSqlTokens.QUOTED_IDENTIFIER) return null;
        String raw = name.text(text);
        if (raw.length() < 2) return null;
        char quote = raw.charAt(0);
        return raw.substring(1, raw.length() - 1).replace("" + quote + quote, "" + quote);
    }

    /** The database selected by the last USE that ends before {@code offset}; null when there is none. */
    public static @Nullable String namespaceAt(@NotNull CharSequence text, int offset, @NotNull SqlDialectProfile profile) {
        if (profile.databaseType() != DatabaseType.MYSQL) return null;
        CharSequence before = text.subSequence(0, Math.max(0, Math.min(offset, text.length())));
        if (!containsUse(before)) return null;
        String selected = null;
        List<SqlToken> code = new java.util.ArrayList<>();
        for (SqlToken token : SqlTokenizer.tokenize(before, profile)) {
            if (LatticeSqlTokens.COMMENTS.contains(token.type())) continue;
            if (token.type() == LatticeSqlTokens.PUNCTUATION && before.charAt(token.start()) == ';') {
                String target = target(code, before, profile);
                if (target != null) selected = target;
                code.clear();
            } else code.add(token);
        }
        return selected;
    }

    /** A cheap test that skips tokenizing scripts that cannot hold a USE. */
    private static boolean containsUse(CharSequence text) {
        for (int i = 0; i + 3 <= text.length(); i++) {
            if ((text.charAt(i) | 0x20) == 'u' && (text.charAt(i + 1) | 0x20) == 's' && (text.charAt(i + 2) | 0x20) == 'e') return true;
        }
        return false;
    }
}
