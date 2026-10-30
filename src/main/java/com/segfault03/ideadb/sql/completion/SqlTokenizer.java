package com.segfault03.ideadb.sql.completion;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLexer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexes text with a dialect's rules. The editor's PSI is dialect-blind, so analysis re-lexes the text itself; comments
 * and strings are kept (the caret may sit in one), whitespace is dropped.
 */
final class SqlTokenizer {
    private SqlTokenizer() {}

    static @NotNull List<SqlToken> tokenize(@NotNull CharSequence text, @NotNull SqlDialectProfile profile) {
        LatticeSqlLexer lexer = new LatticeSqlLexer(profile);
        lexer.start(text, 0, text.length(), 0);
        List<SqlToken> tokens = new ArrayList<>();
        int count = 0;
        for (IElementType type = lexer.getTokenType(); type != null; lexer.advance(), type = lexer.getTokenType()) {
            if ((++count & 0x3FF) == 0) com.intellij.openapi.progress.ProgressManager.checkCanceled();
            if (type != TokenType.WHITE_SPACE) tokens.add(new SqlToken(type, lexer.getTokenStart(), lexer.getTokenEnd()));
        }
        return tokens;
    }
}
