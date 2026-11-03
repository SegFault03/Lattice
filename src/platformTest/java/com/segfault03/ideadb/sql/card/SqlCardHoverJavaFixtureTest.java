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

    /** The caret placed inside the card, as a click or the arrow keys would. */
    private void caretIn(RangeHighlighter card) {
        myFixture.getEditor().getCaretModel().moveToOffset(card.getStartOffset() + 5);
    }

    private SqlCardHoverController focusedController() {
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.focusedForTests(true);
        return controller;
    }

    private static void actionPerformedInTheToolbar() {
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("com.segfault03.lattice.sql.card.copy");
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
                new com.intellij.openapi.actionSystem.Presentation(), SqlCardHoverController.PLACE, com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR, null);
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC)
                .afterActionPerformed(action, event, com.intellij.openapi.actionSystem.AnActionResult.PERFORMED);
    }

    public void testTheToolbarShowsWhileTheCaretIsInACardAndLeavesWithIt() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        caretIn(cards.get(0));
        assertSame(cards.get(0).getUserData(SqlCardPass.CARD), controller.shownCard());
        caretIn(cards.get(1));
        assertSame("the toolbar follows the caret to the next card", cards.get(1).getUserData(SqlCardPass.CARD), controller.shownCard());
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        assertNull("the caret is in no card", controller.shownCard());
    }

    public void testAnEditorWithoutTheFocusShowsNoToolbarForItsCaret() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = SqlCardHoverController.of(myFixture.getEditor());
        controller.focusedForTests(false);
        caretIn(cards.get(0));
        assertNull(controller.shownCard());
    }

    public void testTypingInTheCardHidesTheToolbarAndItReturnsOnceTheCardIsRead() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        caretIn(cards.get(0));
        assertNotNull(controller.shownCard());
        myFixture.type(" ");
        assertNull("it would sit over the text being typed, with actions on SQL that has changed", controller.shownCard());
        myFixture.doHighlighting();
        assertNotNull("the card has been read again and the caret is still in it", controller.shownCard());
    }

    public void testScrollingHidesTheToolbarAndItReturnsWhenTheEditorIsStill() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        caretIn(cards.get(0));
        assertNotNull(controller.shownCard());
        controller.scrolledForTests();
        assertNull(controller.shownCard());
        controller.quietElapsedForTests();
        assertNotNull(controller.shownCard());
    }

    public void testAfterAnActionTheToolbarStaysClosedWhileTheCaretStaysInTheCard() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        caretIn(cards.get(0));
        assertNotNull(controller.shownCard());
        actionPerformedInTheToolbar();
        assertNull(controller.shownCard());
        myFixture.getEditor().getCaretModel().moveToOffset(cards.get(0).getStartOffset() + 8);
        assertNull("still in the card the action closed", controller.shownCard());
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        caretIn(cards.get(0));
        assertNotNull("back in the card after leaving it", controller.shownCard());
    }

    public void testTheCardUnderTheMouseWinsOverTheCardTheCaretIsIn() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        caretIn(cards.get(1));
        assertSame(cards.get(1).getUserData(SqlCardPass.CARD), controller.shownCard());
        controller.hoverForTests(cards.get(0));
        assertSame(cards.get(0).getUserData(SqlCardPass.CARD), controller.shownCard());
        controller.unhoverForTests();
        assertSame("the mouse is gone, the caret's card is back", cards.get(1).getUserData(SqlCardPass.CARD), controller.shownCard());
    }

    public void testHoveringACardStillShowsItsToolbarWithTheCaretElsewhere() {
        List<RangeHighlighter> cards = cards();
        SqlCardHoverController controller = focusedController();
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        controller.hoverForTests(cards.get(0));
        assertSame(cards.get(0).getUserData(SqlCardPass.CARD), controller.shownCard());
        controller.unhoverForTests();
        assertNull(controller.shownCard());
    }

    /** A click in the editor is a click outside the popup; the controller, not the platform, closes it. */
    public void testAClickInTheEditorDoesNotCloseThePopup() {
        var popup = SqlCardHoverController.newPopup(new javax.swing.JPanel());
        try {
            assertFalse(((com.intellij.ui.popup.AbstractPopup) popup).isCancelOnClickOutside());
            assertFalse("it never takes the focus from the editor", popup.isFocused());
        } finally {
            popup.dispose();
        }
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
