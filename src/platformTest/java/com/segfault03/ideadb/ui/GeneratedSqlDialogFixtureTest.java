package com.segfault03.ideadb.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;

import javax.swing.JTextPane;
import javax.swing.text.StyleConstants;
import java.awt.Color;

/** A Swing pane that shows generated SQL colors names the connection's schema knows the way an editor does. */
public class GeneratedSqlDialogFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    private static final String SQL = "select b.id, b.title, b.author_id from books b";

    private static Color colorAt(JTextPane pane, String word) {
        return StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(SQL.indexOf(word)).getAttributes());
    }

    private static Color expected(Role role) {
        return SqlSemanticColors.attributes(EditorColorsManager.getInstance().getGlobalScheme(), role).getForegroundColor();
    }

    public void testNamesTheSchemaKnowsTakeTheEditorsRoleColors() {
        loadSchema();
        JTextPane pane = GeneratedSqlDialog.sqlView(SQL, h2, null);
        assertEquals(expected(Role.TABLE), colorAt(pane, "books"));
        assertEquals(expected(Role.ALIAS), colorAt(pane, "b.id"));
        assertEquals(expected(Role.PRIMARY_KEY), colorAt(pane, "id"));
        assertEquals(expected(Role.TEXT), colorAt(pane, "title"));
        assertEquals(expected(Role.FOREIGN_KEY), colorAt(pane, "author_id"));
    }

    public void testASchemeColorChangeShowsInThePaneOnItsNextPaint() {
        loadSchema();
        var scheme = EditorColorsManager.getInstance().getGlobalScheme();
        var before = scheme.getAttributes(Role.TABLE.key());
        try {
            scheme.setAttributes(Role.TABLE.key(), new com.intellij.openapi.editor.markup.TextAttributes(new Color(0x123456), null, null, null, 0));
            assertEquals(new Color(0x123456), colorAt(GeneratedSqlDialog.sqlView(SQL, h2, null), "books"));
        } finally {
            scheme.setAttributes(Role.TABLE.key(), before);
        }
    }

    public void testWithNoSchemaReadTheSyntaxColorsAloneApply() {
        JTextPane pane = GeneratedSqlDialog.sqlView(SQL, h2, null);
        assertEquals("a name stays the text color until the schema is cached", pane.getForeground(), colorAt(pane, "books"));
        assertNotSame(pane.getForeground(), colorAt(pane, "select"));
    }
}
