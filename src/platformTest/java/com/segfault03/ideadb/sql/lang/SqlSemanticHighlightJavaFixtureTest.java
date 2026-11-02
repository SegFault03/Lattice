package com.segfault03.ideadb.sql.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Names the schema knows are colored by role in a SQL file and in SQL inside a string; with no connection nothing is added. */
public class SqlSemanticHighlightJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, PUBLISHED DATE, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))",
                "CREATE VIEW RECENT AS SELECT ID, TITLE FROM BOOKS"};
    }

    private static final String QUERY = "select b.id, b.title, b.author_id, b.published from books b join authors a on a.id = b.author_id";

    private EditorColorsScheme scheme() { return EditorColorsManager.getInstance().getGlobalScheme(); }

    /** "text:ROLE" for every highlight that gives a name a role, in text order, whichever PSI the highlight is on. */
    private List<String> roles() {
        List<HighlightInfo> infos = new ArrayList<>(myFixture.doHighlighting());
        infos.sort(Comparator.comparingInt(info -> info.startOffset));
        String text = myFixture.getEditor().getDocument().getText();
        List<String> found = new ArrayList<>();
        for (HighlightInfo info : infos) {
            TextAttributes forced = info.forcedTextAttributes;
            if (forced == null) continue;
            for (Role role : Role.values()) {
                TextAttributes expected = SqlSemanticColors.attributes(scheme(), role);
                if (expected.getForegroundColor().equals(forced.getForegroundColor()) && expected.getFontType() == forced.getFontType()) {
                    found.add(text.substring(info.startOffset, info.endOffset) + ":" + role);
                    break;
                }
            }
        }
        return found;
    }

    public void testNamesOfAFileWithAConnectionAreColoredByRole() {
        loadSchema();
        myFixture.configureByText("query.sql", QUERY);
        assertEquals(List.of("b:ALIAS", "id:PRIMARY_KEY", "b:ALIAS", "title:TEXT", "b:ALIAS", "author_id:FOREIGN_KEY", "b:ALIAS", "published:TEMPORAL",
                "books:TABLE", "b:ALIAS", "authors:TABLE", "a:ALIAS", "a:ALIAS", "id:PRIMARY_KEY", "b:ALIAS", "author_id:FOREIGN_KEY"), roles());
    }

    public void testAViewIsToldApartFromATable() {
        loadSchema();
        myFixture.configureByText("query.sql", "select title from recent");
        assertEquals(List.of("title:TEXT", "recent:VIEW"), roles());
    }

    public void testAFileWithoutAConnectionKeepsTheTokenColors() {
        loadSchema();
        myFixture.configureByText("query.sql", QUERY);
        SqlBindingsState.getInstance(getProject()).bindFileToDialect(myFixture.getFile().getVirtualFile(), SqlDialects.H2.id());
        assertEquals(List.of(), roles());
    }

    public void testSwitchingHighlightingOffRemovesTheColors() {
        loadSchema();
        myFixture.configureByText("query.sql", QUERY);
        assertFalse(roles().isEmpty());
        var settings = com.segfault03.ideadb.sql.settings.SqlEditorSettings.getInstance();
        settings.setHighlighting(false);
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject()).restart();
        assertEquals(List.of(), roles());
    }

    public void testSqlInAJavaStringIsColoredToo() {
        loadSchema();
        myFixture.configureByText("A.java", "import java.sql.*;\nclass A {\n  void f(Connection c) throws Exception {\n"
                + "    c.prepareStatement(\"select title from books where id = ?\");\n  }\n}");
        assertEquals(List.of("title:TEXT", "books:TABLE", "id:PRIMARY_KEY"), roles());
    }

    public void testASchemeWithoutTheColorsGetsDefaultsThatSuitItsBrightness() {
        EditorColorsScheme scheme = scheme();
        var before = scheme.getAttributes(Role.TABLE.key());
        try {
            scheme.setAttributes(Role.TABLE.key(), new TextAttributes());
            boolean dark = SqlSemanticColors.isDark(scheme);
            TextAttributes fallback = SqlSemanticColors.attributes(scheme, Role.TABLE);
            assertEquals(Role.TABLE.defaultColor(dark), fallback.getForegroundColor());
            assertEquals(Role.TABLE.defaultFontType(), fallback.getFontType());
            scheme.setAttributes(Role.TABLE.key(), new TextAttributes(new Color(0x123456), null, null, null, 0));
            assertEquals("the scheme's own color wins", new Color(0x123456), SqlSemanticColors.attributes(scheme, Role.TABLE).getForegroundColor());
            scheme.setAttributes(Role.TABLE.key(), new TextAttributes(scheme.getDefaultForeground(), null, null, null, 0));
            assertEquals("the plain text color says nothing", Role.TABLE.defaultColor(dark), SqlSemanticColors.attributes(scheme, Role.TABLE).getForegroundColor());
        } finally {
            scheme.setAttributes(Role.TABLE.key(), before);
        }
    }
}
