package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

/** Which card the mouse is over, and what hides the toolbar. */
public class SqlCardHoverJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private static final String TWO_ON_ONE_LINE = "import java.sql.*;\nclass A {\n  void f(Connection c) throws Exception {\n"
            + "    c.prepareStatement(\"select 1 from dual\"); c.prepareStatement(\"select 2 from dual\");\n  }\n}";

    private List<RangeHighlighter> cards() {
        myFixture.configureByText("A.java", TWO_ON_ONE_LINE);
        myFixture.doHighlighting();
        List<RangeHighlighter> highlighters = SqlCardPass.highlighters(myFixture.getEditor());
        assertEquals(2, highlighters.size());
        return highlighters;
    }

    private static Point centre(Rectangle bounds) { return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()); }

    public void testCardAtPicksCardUnderPoint() {
        List<RangeHighlighter> cards = cards();
        Editor editor = myFixture.getEditor();
        for (RangeHighlighter highlighter : cards) {
            assertSame(highlighter, SqlCardHoverController.cardAt(editor, centre(SqlCardRenderer.boundsFor(editor, highlighter))));
        }
        assertNull(SqlCardHoverController.cardAt(editor, new Point(1, 1)));
    }

    public void testTypingHidesToolbar() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        assertNotNull("installed for the fixture's editor", controller);
        controller.showForTests(first);
        assertSame(first.getUserData(SqlCardPass.CARD), controller.shownCard());
        assertEquals(Boolean.TRUE, first.getUserData(SqlCardPass.HOVERED));
        myFixture.getEditor().getCaretModel().moveToOffset(TWO_ON_ONE_LINE.indexOf("select 1") + 3);
        myFixture.type(" ");
        assertNull(controller.shownCard());
        assertNull(first.getUserData(SqlCardPass.HOVERED));
    }

    public void testControllerNotInstalledForViewer() {
        myFixture.configureByText("A.java", TWO_ON_ONE_LINE);
        Editor viewer = EditorFactory.getInstance().createViewer(myFixture.getEditor().getDocument(), getProject());
        try {
            assertNull(SqlCardHoverController.of(viewer));
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer);
        }
    }
}
