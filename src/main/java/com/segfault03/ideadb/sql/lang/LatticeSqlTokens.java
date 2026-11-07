package com.segfault03.ideadb.sql.lang;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

public final class LatticeSqlTokens {
    private LatticeSqlTokens() {}
    private static IElementType token(String name) { return new IElementType("SQL_" + name, LatticeSqlLanguage.INSTANCE); }

    public static final IElementType KEYWORD = token("KEYWORD");
    public static final IElementType FUNCTION = token("FUNCTION");
    public static final IElementType TYPE = token("TYPE");
    public static final IElementType IDENTIFIER = token("IDENTIFIER");
    public static final IElementType QUOTED_IDENTIFIER = token("QUOTED_IDENTIFIER");
    public static final IElementType STRING = token("STRING");
    public static final IElementType NUMBER = token("NUMBER");
    public static final IElementType PARAMETER = token("PARAMETER");
    public static final IElementType LINE_COMMENT = token("LINE_COMMENT");
    public static final IElementType BLOCK_COMMENT = token("BLOCK_COMMENT");
    public static final IElementType OPERATOR = token("OPERATOR");
    public static final IElementType PUNCTUATION = token("PUNCTUATION");

    public static final TokenSet COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT);
    public static final TokenSet STRINGS = TokenSet.create(STRING);
    public static final TokenSet NAMES = TokenSet.create(IDENTIFIER, QUOTED_IDENTIFIER);
}
