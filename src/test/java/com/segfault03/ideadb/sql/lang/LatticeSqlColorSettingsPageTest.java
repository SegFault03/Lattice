package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LatticeSqlColorSettingsPageTest {
    @Test void theDemoShowsEveryColoredKindIncludingATypeAndAFunction() {
        var lexer = new LatticeSqlLexer(SqlDialects.MYSQL);
        String demo = new LatticeSqlColorSettingsPage().getDemoText();
        lexer.start(demo, 0, demo.length(), 0);
        Set<IElementType> seen = new HashSet<>();
        for (IElementType type = lexer.getTokenType(); type != null; lexer.advance(), type = lexer.getTokenType()) seen.add(type);
        for (IElementType expected : new IElementType[] {LatticeSqlTokens.KEYWORD, LatticeSqlTokens.FUNCTION, LatticeSqlTokens.TYPE, LatticeSqlTokens.STRING,
                LatticeSqlTokens.NUMBER, LatticeSqlTokens.QUOTED_IDENTIFIER, LatticeSqlTokens.PARAMETER, LatticeSqlTokens.LINE_COMMENT,
                LatticeSqlTokens.BLOCK_COMMENT, LatticeSqlTokens.OPERATOR}) {
            assertTrue(seen.contains(expected), expected + " is missing from the demo");
        }
    }

    @Test void typesInheritKeywordColorsAndFunctionsAnAttributeOfTheirOwn() {
        var type = LatticeSqlSyntaxHighlighter.TYPE.getFallbackAttributeKey();
        var function = LatticeSqlSyntaxHighlighter.FUNCTION.getFallbackAttributeKey();
        assertNotNull(type);
        assertNotNull(function);
        assertNotEquals(type, function);
        assertEquals(DefaultLanguageHighlighterColors.KEYWORD, type, "a type reads like a keyword until the user colors it");
        assertNotEquals(DefaultLanguageHighlighterColors.KEYWORD, function);
        for (var inherited : new TextAttributesKey[] {type, function}) {
            assertNotEquals(DefaultLanguageHighlighterColors.IDENTIFIER, inherited);
            assertNotEquals(DefaultLanguageHighlighterColors.CLASS_NAME, inherited, "class names have no color of their own in the stock schemes");
            assertNotEquals(DefaultLanguageHighlighterColors.FUNCTION_CALL, inherited, "nor do function calls");
        }
    }
}
