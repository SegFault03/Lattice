package com.segfault03.ideadb.sql.lang;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LatticeSqlLexerTest {
    /** Non-blank tokens as {@code KIND(text)}. */
    private static List<String> lex(SqlDialectProfile profile, String text) {
        LatticeSqlLexer lexer = new LatticeSqlLexer(profile);
        lexer.start(text, 0, text.length(), 0);
        List<String> tokens = new ArrayList<>();
        for (IElementType type = lexer.getTokenType(); type != null; lexer.advance(), type = lexer.getTokenType()) {
            if (type == TokenType.WHITE_SPACE) continue;
            tokens.add(type.toString().replaceFirst("^SQL_", "") + "(" + text.substring(lexer.getTokenStart(), lexer.getTokenEnd()) + ")");
        }
        return tokens;
    }

    @Test void genericSqlClassifiesWords() {
        assertEquals(List.of("KEYWORD(select)", "IDENTIFIER(id)", "PUNCTUATION(,)", "FUNCTION(count)", "PUNCTUATION(()", "OPERATOR(*)",
                "PUNCTUATION())", "KEYWORD(from)", "IDENTIFIER(t)"), lex(SqlDialects.ANSI, "select id, count(*) from t"));
    }

    @Test void aFunctionNameIsOnlyAFunctionBeforeACallParenthesis() {
        assertEquals(List.of("IDENTIFIER(count)"), lex(SqlDialects.ANSI, "count"));
        assertEquals(List.of("FUNCTION(count)", "PUNCTUATION(()", "IDENTIFIER(x)", "PUNCTUATION())"), lex(SqlDialects.ANSI, "count (x)"));
    }

    @Test void typesAreNotFunctions() {
        assertEquals(List.of("TYPE(varchar)", "PUNCTUATION(()", "NUMBER(20)", "PUNCTUATION())"), lex(SqlDialects.ANSI, "varchar(20)"));
    }

    @Test void aTypeNameIsATypeEvenWhenTheDialectAlsoListsItAsAKeyword() {
        assertEquals(List.of("TYPE(binary)", "PUNCTUATION(()", "NUMBER(16)", "PUNCTUATION())"), lex(SqlDialects.MYSQL, "binary(16)"));
        assertEquals(List.of("IDENTIFIER(a)", "TYPE(bigint)", "KEYWORD(primary)", "KEYWORD(key)"), lex(SqlDialects.MYSQL, "a bigint primary key"));
        assertEquals(List.of("KEYWORD(set)", "KEYWORD(identity)"), lex(SqlDialects.H2, "set identity"), "syntax words stay keywords");
    }

    @Test void aFunctionNameBeforeAParenthesisIsAFunctionEvenWhenTheDialectAlsoListsItAsAKeyword() {
        assertEquals(List.of("FUNCTION(left)", "PUNCTUATION(()", "IDENTIFIER(a)", "PUNCTUATION(,)", "NUMBER(2)", "PUNCTUATION())"), lex(SqlDialects.MYSQL, "left(a, 2)"));
        assertEquals(List.of("KEYWORD(left)", "KEYWORD(join)", "IDENTIFIER(t)"), lex(SqlDialects.MYSQL, "left join t"), "without a parenthesis it is the join keyword");
        assertEquals(List.of("KEYWORD(replace)", "KEYWORD(into)", "IDENTIFIER(t)"), lex(SqlDialects.MYSQL, "replace into t"));
        assertEquals(List.of("KEYWORD(values)", "PUNCTUATION(()", "NUMBER(1)", "PUNCTUATION())"), lex(SqlDialects.MYSQL, "values (1)"), "VALUES ( is syntax");
        assertEquals(List.of("KEYWORD(in)", "PUNCTUATION(()", "NUMBER(1)", "PUNCTUATION())"), lex(SqlDialects.ANSI, "in (1)"));
    }

    @Test void theWordsTheEditorFeaturesReadAsKeywordsAreNeverReclassified() {
        // Completion and validation look for these as KEYWORD tokens (clause starts, statement kinds, join words, literals).
        var read = List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "HAVING", "BY", "SET", "VALUES", "INTO", "UPDATE", "INSERT", "DELETE", "CREATE", "ALTER",
                "DROP", "TABLE", "WITH", "AS", "IF", "NOT", "EXISTS", "ONLY", "NULL", "TRUE", "FALSE", "UNION", "INTERSECT", "EXCEPT", "USING", "LIMIT", "OFFSET",
                "ORDER", "GROUP", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "REPLACE", "MERGE", "RETURNING", "DISTINCT", "ALL", "ANY", "OVER", "IS", "IN");
        for (SqlDialectProfile profile : SqlDialects.all()) {
            for (String word : read) {
                if (!profile.keywords().contains(word)) continue;
                for (boolean paren : new boolean[] {false}) {
                    assertEquals(SqlDialectProfile.WordKind.KEYWORD, profile.classify(word, paren), profile.id() + " " + word);
                }
                assertFalse(profile.types().contains(word) && !word.equals("SET"), profile.id() + " " + word + " would become a type");
            }
        }
        // before a parenthesis only the words that are also function names change, and none of the syntax words does
        for (SqlDialectProfile profile : SqlDialects.all()) {
            for (String word : List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "IN", "EXISTS", "VALUES", "ANY", "ALL", "USING", "AS", "OVER", "NOT", "AND", "OR", "KEY",
                    "PRIMARY", "UNIQUE", "CHECK", "REFERENCES", "FOREIGN", "IF", "CASE", "SET", "INSERT", "UPDATE", "DELETE", "UNION", "WITH", "FILTER", "WITHIN", "ROW")) {
                if (profile.keywords().contains(word)) assertEquals(SqlDialectProfile.WordKind.KEYWORD, profile.classify(word, true), profile.id() + " " + word + "(");
            }
        }
    }

    @Test void mysqlTreatsHashAsCommentBackticksAsIdentifiersAndDoubleQuotesAsStrings() {
        assertEquals(List.of("LINE_COMMENT(# note)", "KEYWORD(select)", "QUOTED_IDENTIFIER(`a b`)", "PUNCTUATION(,)", "STRING(\"x\")",
                "KEYWORD(from)", "IDENTIFIER(t)"), lex(SqlDialects.MYSQL, "# note\nselect `a b`, \"x\" from t"));
        assertEquals(List.of("QUOTED_IDENTIFIER(\"x\")"), lex(SqlDialects.POSTGRESQL, "\"x\""));
        assertEquals(List.of("QUOTED_IDENTIFIER(\"x\")"), lex(SqlDialects.ANSI, "\"x\""));
    }

    @Test void mysqlDoubleDashIsACommentOnlyBeforeWhitespace() {
        assertEquals(List.of("NUMBER(1)", "OPERATOR(--)", "NUMBER(1)"), lex(SqlDialects.MYSQL, "1--1"));
        assertEquals(List.of("NUMBER(1)", "LINE_COMMENT(-- x)"), lex(SqlDialects.MYSQL, "1-- x"));
        assertEquals(List.of("NUMBER(1)", "LINE_COMMENT(--1)"), lex(SqlDialects.ANSI, "1--1"));
    }

    @Test void mysqlStringsAllowBackslashEscapesOthersDoNot() {
        assertEquals(List.of("STRING('it\\'s')"), lex(SqlDialects.MYSQL, "'it\\'s'"));
        assertEquals(List.of("STRING('it\\')", "IDENTIFIER(s)", "STRING(')"), lex(SqlDialects.ANSI, "'it\\'s'"));
        assertEquals(List.of("STRING('it''s')"), lex(SqlDialects.ANSI, "'it''s'"));
    }

    @Test void mysqlVariablesAndExecutableComments() {
        assertEquals(List.of("KEYWORD(select)", "PARAMETER(@a)", "PUNCTUATION(,)", "PARAMETER(@@global)", "PUNCTUATION(.)", "IDENTIFIER(x)",
                "BLOCK_COMMENT(/*!40101 SET x */)"), lex(SqlDialects.MYSQL, "select @a, @@global.x /*!40101 SET x */"));
    }

    @Test void postgresqlDollarQuotingCastsEscapeStringsAndNumberedParameters() {
        assertEquals(List.of("STRING($$a'b$$)"), lex(SqlDialects.POSTGRESQL, "$$a'b$$"));
        assertEquals(List.of("STRING($fn$ x $$ y $fn$)"), lex(SqlDialects.POSTGRESQL, "$fn$ x $$ y $fn$"));
        assertEquals(List.of("IDENTIFIER(x)", "OPERATOR(::)", "TYPE(int)"), lex(SqlDialects.POSTGRESQL, "x::int"));
        assertEquals(List.of("STRING(E'a\\'b')"), lex(SqlDialects.POSTGRESQL, "E'a\\'b'"));
        assertEquals(List.of("PARAMETER($1)", "OPERATOR(=)", "PARAMETER($2)"), lex(SqlDialects.POSTGRESQL, "$1 = $2"));
        assertEquals(List.of("IDENTIFIER(a)", "OPERATOR(#)", "IDENTIFIER(b)"), lex(SqlDialects.POSTGRESQL, "a # b"));
        assertEquals(List.of("BLOCK_COMMENT(/* a /* b */ c */)", "IDENTIFIER(x)"), lex(SqlDialects.POSTGRESQL, "/* a /* b */ c */ x"));
        assertEquals(List.of("BLOCK_COMMENT(/* a /* b */)", "IDENTIFIER(c)", "OPERATOR(*/)"), lex(SqlDialects.ANSI, "/* a /* b */ c */"));
    }

    @Test void h2AllowsSlashSlashCommentsAndDollarStringsHsqldbDoesNot() {
        assertEquals(List.of("KEYWORD(select)", "NUMBER(1)", "LINE_COMMENT(// hi)"), lex(SqlDialects.H2, "select 1 // hi"));
        assertEquals(List.of("NUMBER(1)", "OPERATOR(//)", "IDENTIFIER(hi)"), lex(SqlDialects.HSQLDB, "1 // hi"));
        assertEquals(List.of("STRING($$x$$)"), lex(SqlDialects.H2, "$$x$$"));
    }

    @Test void namedParametersCastsAndSlicesAreDistinguished() {
        assertEquals(List.of("KEYWORD(where)", "IDENTIFIER(id)", "OPERATOR(=)", "PARAMETER(:id)"), lex(SqlDialects.ANSI, "where id = :id"));
        assertEquals(List.of("IDENTIFIER(a)", "OPERATOR(:=)", "NUMBER(1)"), lex(SqlDialects.MYSQL, "a:=1"));
        assertEquals(List.of("IDENTIFIER(x)", "PUNCTUATION([)", "NUMBER(1)", "PUNCTUATION(:)", "NUMBER(2)", "PUNCTUATION(])"), lex(SqlDialects.POSTGRESQL, "x[1:2]"));
        assertEquals(List.of("PARAMETER(?)"), lex(SqlDialects.H2, "?"));
    }

    @Test void numbers() {
        assertEquals(List.of("NUMBER(1)", "NUMBER(2.5)", "NUMBER(1e10)", "NUMBER(0xFF)", "NUMBER(.5)", "NUMBER(3E-2)"), lex(SqlDialects.ANSI, "1 2.5 1e10 0xFF .5 3E-2"));
    }

    @Test void unterminatedConstructsRunToTheEnd() {
        assertEquals(List.of("STRING('abc)"), lex(SqlDialects.ANSI, "'abc"));
        assertEquals(List.of("BLOCK_COMMENT(/* x)"), lex(SqlDialects.ANSI, "/* x"));
        assertEquals(List.of("STRING($$ abc)"), lex(SqlDialects.POSTGRESQL, "$$ abc"));
        assertEquals(List.of("QUOTED_IDENTIFIER(\"abc)"), lex(SqlDialects.ANSI, "\"abc"));
    }

    @Test void dialectsAgreeOnWhatTheyShare() {
        String shared = "SELECT a, b FROM t WHERE a > 1 AND b IS NOT NULL ORDER BY a;";
        assertEquals(lex(SqlDialects.ANSI, shared), lex(SqlDialects.HSQLDB, shared));
        assertEquals(lex(SqlDialects.ANSI, shared), lex(SqlDialects.POSTGRESQL, shared));
    }

    @Test void dialectKeywordsOnlyApplyToTheirDialect() {
        assertEquals(List.of("KEYWORD(returning)"), lex(SqlDialects.POSTGRESQL, "returning"));
        assertEquals(List.of("IDENTIFIER(returning)"), lex(SqlDialects.MYSQL, "returning"));
        assertEquals(List.of("KEYWORD(auto_increment)"), lex(SqlDialects.MYSQL, "auto_increment"));
        assertEquals(List.of("IDENTIFIER(auto_increment)"), lex(SqlDialects.POSTGRESQL, "auto_increment"));
    }

    @Test void tokensAlwaysCoverTheInputAndRestartingAtAnyBoundaryGivesTheSameTokens() {
        String alphabet = "ab_ 1.9\n'\"`$#:/*-?@\\;(),=<>eExX\t[]";
        Random random = new Random(42);
        for (SqlDialectProfile profile : SqlDialects.all()) {
            for (int round = 0; round < 3000; round++) {
                StringBuilder text = new StringBuilder();
                for (int i = random.nextInt(41); i > 0; i--) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                String input = text.toString();
                List<int[]> tokens = tokens(profile, input, 0);
                int expected = 0;
                for (int[] token : tokens) {
                    assertEquals(expected, token[0], profile.id() + " gap in: " + input);
                    assertTrue(token[1] > token[0], profile.id() + " empty token in: " + input);
                    expected = token[1];
                }
                assertEquals(input.length(), expected, profile.id() + " does not cover: " + input);
                for (int[] token : tokens) {
                    List<int[]> restarted = tokens(profile, input, token[0]);
                    List<int[]> suffix = tokens.subList(tokens.indexOf(token), tokens.size());
                    assertEquals(suffix.size(), restarted.size(), profile.id() + " restart at " + token[0] + " in: " + input);
                    for (int i = 0; i < suffix.size(); i++) assertArrayEquals(suffix.get(i), restarted.get(i), profile.id() + " restart at " + token[0] + " in: " + input);
                }
            }
        }
    }

    private static List<int[]> tokens(SqlDialectProfile profile, String text, int from) {
        LatticeSqlLexer lexer = new LatticeSqlLexer(profile);
        lexer.start(text, from, text.length(), 0);
        List<int[]> tokens = new ArrayList<>();
        for (; lexer.getTokenType() != null; lexer.advance()) {
            assertEquals(0, lexer.getState());
            tokens.add(new int[]{lexer.getTokenStart(), lexer.getTokenEnd()});
        }
        return tokens;
    }
}
