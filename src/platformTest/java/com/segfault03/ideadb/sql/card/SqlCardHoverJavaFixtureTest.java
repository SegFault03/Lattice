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

    public void testStaleCardIsNotShownAfterEdit() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        myFixture.getEditor().getCaretModel().moveToOffset(TWO_ON_ONE_LINE.indexOf("select 1") + 3);
        myFixture.type(" ");
        controller.showForTests(first);
        assertNull("the card's SQL is from before the edit", controller.shownCard());
        myFixture.doHighlighting();
        controller.showForTests(SqlCardPass.highlighters(myFixture.getEditor()).get(0));
        assertNotNull("fresh again after the next pass", controller.shownCard());
    }

    public void testPopupClosedByThePlatformClearsHover() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.showForTests(first);
        controller.popupClosedForTests();
        assertNull(controller.shownCard());
        assertNull(first.getUserData(SqlCardPass.HOVERED));
    }

    public void testToolbarClosesAfterOneOfItsActionsRuns() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.showForTests(first);
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("com.segfault03.lattice.sql.card.copy");
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                new com.intellij.openapi.actionSystem.Presentation(), SqlCardHoverController.PLACE, com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR, null);
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC)
                .afterActionPerformed(action, event, com.intellij.openapi.actionSystem.AnActionResult.PERFORMED);
        assertNull(controller.shownCard());
    }

    public void testOpeningTheMoreMenuKeepsTheToolbar() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.showForTests(first);
        var more = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("com.segfault03.lattice.sql.card.more");
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(more, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                new com.intellij.openapi.actionSystem.Presentation(), SqlCardHoverController.PLACE, com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR, null);
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC)
                .afterActionPerformed(more, event, com.intellij.openapi.actionSystem.AnActionResult.PERFORMED);
        assertNotNull("the menu the group opened would close with the toolbar", controller.shownCard());
    }

    public void testActionChosenInTheMoreMenuClosesTheToolbar() {
        var explain = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("com.segfault03.lattice.sql.run.explain");
        assertTrue(SqlCardHoverController.closesToolbar(explain,
                com.intellij.openapi.actionSystem.ActionPlaces.getActionGroupPopupPlace(SqlCardHoverController.PLACE)));
        assertFalse(SqlCardHoverController.closesToolbar(explain, "EditorPopup"));
    }

    public void testToolbarStaysForActionsElsewhere() {
        RangeHighlighter first = cards().get(0);
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.showForTests(first);
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("com.segfault03.lattice.sql.card.copy");
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                new com.intellij.openapi.actionSystem.Presentation(), "EditorPopup", com.intellij.openapi.actionSystem.ActionUiKind.POPUP, null);
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC)
                .afterActionPerformed(action, event, com.intellij.openapi.actionSystem.AnActionResult.PERFORMED);
        assertNotNull(controller.shownCard());
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
