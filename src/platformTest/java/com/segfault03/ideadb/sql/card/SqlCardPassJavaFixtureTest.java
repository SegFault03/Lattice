package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.EditorTestUtil;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;

import java.awt.Rectangle;
import java.util.List;

/** The pass that keeps one card highlighter per piece of SQL in an editor. */
public class SqlCardPassJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private static final String TWO_QUERIES = "import java.sql.*;\nclass A {\n"
            + "  void f(Connection c) throws Exception {\n"
            + "    c.prepareStatement(\"select id from orders where id = ?\");\n"
            + "    c.prepareStatement(\"update orders set status = 'x' where id = ?\");\n"
            + "  }\n}";

    private List<TextRange> highlighted() {
        myFixture.doHighlighting();
        return SqlCardPass.highlighters(myFixture.getEditor()).stream().map(RangeHighlighter::getTextRange).toList();
    }

    private List<TextRange> located() {
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        return ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile())).stream().map(SqlCard::range).toList();
    }

    public void testHighlighterPerCard() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        List<TextRange> ranges = highlighted();
        assertEquals(2, ranges.size());
        assertEquals(located(), ranges);
        for (RangeHighlighter highlighter : SqlCardPass.highlighters(myFixture.getEditor())) assertNotNull(highlighter.getUserData(SqlCardPass.CARD));
    }

    public void testRangesFollowEdits() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        highlighted();
        myFixture.getEditor().getCaretModel().moveToOffset(TWO_QUERIES.indexOf("from orders where") + 4);
        myFixture.type(" ");
        assertEquals(located(), highlighted());
    }

    public void testCardFollowsTypingBeforeNextPass() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        highlighted();
        Editor editor = myFixture.getEditor();
        RangeHighlighter first = SqlCardPass.highlighters(editor).get(0);
        editor.getCaretModel().moveToOffset(TWO_QUERIES.indexOf("from orders where") + 4);
        myFixture.type("xxxxxxxxxxxxxxxxxxxx");
        Rectangle bounds = SqlCardRenderer.boundsFor(editor, first);
        int literalEnd = editor.offsetToXY(first.getEndOffset()).x;
        assertTrue("card reaches the literal's new end: " + bounds + " vs " + literalEnd, bounds.x + bounds.width >= literalEnd);
    }

    public void testCardShrinksWithDeletionBeforeNextPass() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        highlighted();
        Editor editor = myFixture.getEditor();
        RangeHighlighter first = SqlCardPass.highlighters(editor).get(0);
        int from = TWO_QUERIES.indexOf(" where id = ?\");");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(),
                () -> editor.getDocument().deleteString(from, from + " where id = ?".length()));
        Rectangle bounds = SqlCardRenderer.boundsFor(editor, first);
        int literalEnd = editor.offsetToXY(first.getEndOffset()).x;
        assertTrue("card stops at the literal's end: " + bounds + " vs " + literalEnd,
                bounds.x + bounds.width <= literalEnd + SqlCardGeometry.PADDING);
    }

    public void testUnchangedPassKeepsHighlighters() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        highlighted();
        List<RangeHighlighter> before = SqlCardPass.highlighters(myFixture.getEditor());
        highlighted();
        List<RangeHighlighter> after = SqlCardPass.highlighters(myFixture.getEditor());
        assertEquals(2, after.size());
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), after.get(i));
    }

    public void testNoCardsWhenSettingOff() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        assertEquals(2, highlighted().size());
        SqlEditorSettings.getInstance().setSqlCards(false);
        assertEquals(List.of(), highlighted());
    }

    public void testNoCardsWhenLatticeSqlDisabled() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        SqlEditorSettings.getInstance().setEnabled(false);
        assertEquals(List.of(), highlighted());
    }

    public void testNoCardsInViewer() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        Editor viewer = EditorFactory.getInstance().createViewer(myFixture.getEditor().getDocument(), getProject());
        try {
            assertNull(new SqlCardPassFactory().createHighlightingPass(myFixture.getFile(), viewer));
        } finally {
            EditorFactory.getInstance().releaseEditor(viewer);
        }
    }

    public void testNoCardsForInMemorySqlInputs() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        assertFalse(SqlCardPassFactory.showsCards(myFixture.getEditor(), new com.intellij.testFramework.LightVirtualFile("lattice-input.sql", "select 1")));
        assertTrue(SqlCardPassFactory.showsCards(myFixture.getEditor(), myFixture.getFile().getVirtualFile()));
    }

    public void testSoftWrapCoversAllVisualLines() {
        myFixture.configureByText("A.java", TWO_QUERIES);
        EditorTestUtil.configureSoftWraps(myFixture.getEditor(), 30);
        highlighted();
        RangeHighlighter first = SqlCardPass.highlighters(myFixture.getEditor()).get(0);
        Rectangle bounds = SqlCardRenderer.boundsFor(myFixture.getEditor(), first);
        Editor editor = myFixture.getEditor();
        assertTrue(bounds.toString(), bounds.height >= 2 * editor.getLineHeight());
        int firstLine = editor.offsetToVisualPosition(first.getStartOffset()).line;
        int wrapAt = com.intellij.openapi.editor.ex.util.EditorUtil.getVisualLineEndOffset(editor, firstLine);
        int lastCharBeforeWrap = editor.offsetToXY(wrapAt, false, true).x;
        assertTrue("card reaches the soft wrap: " + bounds + " vs " + lastCharBeforeWrap, bounds.x + bounds.width >= lastCharBeforeWrap);
    }
}
