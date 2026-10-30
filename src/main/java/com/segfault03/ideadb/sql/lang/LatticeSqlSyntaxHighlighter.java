package com.segfault03.ideadb.sql.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/** Colors the tokens of one dialect. With highlighting switched off it still lexes (for the editor) but adds no color. */
public final class LatticeSqlSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = createTextAttributesKey("LATTICE_SQL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey FUNCTION = createTextAttributesKey("LATTICE_SQL_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL);
    public static final TextAttributesKey TYPE = createTextAttributesKey("LATTICE_SQL_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey STRING = createTextAttributesKey("LATTICE_SQL_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = createTextAttributesKey("LATTICE_SQL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey QUOTED_IDENTIFIER = createTextAttributesKey("LATTICE_SQL_QUOTED_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey PARAMETER = createTextAttributesKey("LATTICE_SQL_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER);
    public static final TextAttributesKey LINE_COMMENT = createTextAttributesKey("LATTICE_SQL_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey BLOCK_COMMENT = createTextAttributesKey("LATTICE_SQL_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    public static final TextAttributesKey OPERATOR = createTextAttributesKey("LATTICE_SQL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    private final SqlDialectProfile profile;
    private final boolean colored;

    public LatticeSqlSyntaxHighlighter(@NotNull SqlDialectProfile profile, boolean colored) {
        this.profile = profile;
        this.colored = colored;
    }

    public @NotNull SqlDialectProfile profile() { return profile; }

    @Override public @NotNull Lexer getHighlightingLexer() { return new LatticeSqlLexer(profile); }

    @Override public TextAttributesKey @NotNull [] getTokenHighlights(IElementType type) {
        if (!colored) return TextAttributesKey.EMPTY_ARRAY;
        if (type == LatticeSqlTokens.KEYWORD) return pack(KEYWORD);
        if (type == LatticeSqlTokens.FUNCTION) return pack(FUNCTION);
        if (type == LatticeSqlTokens.TYPE) return pack(TYPE);
        if (type == LatticeSqlTokens.STRING) return pack(STRING);
        if (type == LatticeSqlTokens.NUMBER) return pack(NUMBER);
        if (type == LatticeSqlTokens.QUOTED_IDENTIFIER) return pack(QUOTED_IDENTIFIER);
        if (type == LatticeSqlTokens.PARAMETER) return pack(PARAMETER);
        if (type == LatticeSqlTokens.LINE_COMMENT) return pack(LINE_COMMENT);
        if (type == LatticeSqlTokens.BLOCK_COMMENT) return pack(BLOCK_COMMENT);
        if (type == LatticeSqlTokens.OPERATOR) return pack(OPERATOR);
        return TextAttributesKey.EMPTY_ARRAY;
    }
}
