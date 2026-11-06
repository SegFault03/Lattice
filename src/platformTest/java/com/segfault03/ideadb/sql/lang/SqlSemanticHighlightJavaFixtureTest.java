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

    /** The color the highlight on the first occurrence of the word carries, or null when it has none. */
    private Color forcedColorOf(String word) {
        String text = myFixture.getEditor().getDocument().getText();
        int at = text.indexOf(word);
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.startOffset == at && info.forcedTextAttributes != null) return info.forcedTextAttributes.getForegroundColor();
        }
        return null;
    }

    public void testTheChosenFamilyColorsTablesViewsAndAliases() {
        loadSchema();
        myFixture.configureByText("query.sql", "select title from recent r join books b on b.id = r.id");
        var settings = com.segfault03.ideadb.sql.settings.SqlEditorSettings.getInstance();
        var seen = new java.util.HashSet<Color>();
        for (var family : com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily.values()) {
            settings.schemaColorFamily(family);
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject()).restart();
            assertEquals(family + " view", SqlSemanticColors.attributes(scheme(), Role.VIEW, family).getForegroundColor(), forcedColorOf("recent"));
            assertEquals(family + " table", SqlSemanticColors.attributes(scheme(), Role.TABLE, family).getForegroundColor(), forcedColorOf("books"));
            assertEquals(family + " alias", SqlSemanticColors.attributes(scheme(), Role.ALIAS, family).getForegroundColor(), forcedColorOf("r join"));
            assertTrue(family + " looks like another family", seen.add(forcedColorOf("books")));
            assertEquals("columns do not change with the family", SqlSemanticColors.attributes(scheme(), Role.TEXT, family).getForegroundColor(), forcedColorOf("title"));
        }
    }

    public void testTheNeutralFamilyUsesTheSchemesTextColor() {
        EditorColorsScheme scheme = scheme();
        var neutral = com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily.NEUTRAL;
        assertEquals(scheme.getDefaultForeground(), SqlSemanticColors.attributes(scheme, Role.TABLE, neutral).getForegroundColor());
        assertEquals(java.awt.Font.BOLD, SqlSemanticColors.attributes(scheme, Role.TABLE, neutral).getFontType());
        assertFalse("aliases are dimmer than the text", scheme.getDefaultForeground().equals(SqlSemanticColors.attributes(scheme, Role.ALIAS, neutral).getForegroundColor()));
    }

    public void testTheFamilyIsKeptInTheSettings() {
        var settings = com.segfault03.ideadb.sql.settings.SqlEditorSettings.getInstance();
        assertEquals(com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily.CORAL, settings.schemaColorFamily());
        settings.schemaColorFamily(com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily.PERIWINKLE);
        var restored = new com.segfault03.ideadb.sql.settings.SqlEditorSettings.State();
        restored.schemaColorFamily = "not a family";
        settings.loadState(restored);
        assertEquals("an unknown value falls back to coral", com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily.CORAL, settings.schemaColorFamily());
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
