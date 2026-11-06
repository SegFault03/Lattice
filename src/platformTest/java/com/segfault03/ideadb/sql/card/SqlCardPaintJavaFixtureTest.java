package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/** What a card looks like once painted: above the IDE's injected-fragment background, below the selection. */
public class SqlCardPaintJavaFixtureTest extends H2SqlFixtureTestCase {
    private static final Color FILL = new Color(0xFF0000), BORDER = new Color(0x00FF00), FRAGMENT = new Color(0x00FFFF), SELECTION = new Color(0x0000FF);
    private static final String SOURCE = "import java.sql.*;\nclass A {\n  void f(Connection c) throws Exception {\n"
            + "    c.prepareStatement(\"select id, status from orders where id = ?\");\n  }\n}";

    @Override protected String[] schemaStatements() { return new String[0]; }

    private EditorEx editor() {
        myFixture.configureByText("A.java", SOURCE);
        EditorEx editor = (EditorEx) myFixture.getEditor();
        EditorColorsScheme scheme = (EditorColorsScheme) com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().clone();
        TextAttributes card = new TextAttributes();
        card.setBackgroundColor(FILL);
        card.setEffectColor(BORDER);
        scheme.setAttributes(SqlCardColors.CARD, card);
        TextAttributes fragment = new TextAttributes();
        fragment.setBackgroundColor(FRAGMENT);
        scheme.setAttributes(EditorColors.INJECTED_LANGUAGE_FRAGMENT, fragment);
        scheme.setColor(EditorColors.SELECTION_BACKGROUND_COLOR, SELECTION);
        scheme.setColor(EditorColors.CARET_ROW_COLOR, null);
        editor.setColorsScheme(scheme);
        editor.getCaretModel().moveToOffset(0);
        myFixture.doHighlighting();
        return editor;
    }

    private static BufferedImage paint(EditorEx editor) {
        editor.getScrollPane().setSize(1200, 300);
        editor.getComponent().setSize(1200, 300);
        editor.getContentComponent().setSize(1200, 300);
        editor.getComponent().doLayout();
        BufferedImage image = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        editor.getContentComponent().paint(g);
        g.dispose();
        return image;
    }

    /** A point inside the literal, in the line's top pixels, clear of glyphs. */
    private static Point inside(EditorEx editor, int offset) {
        Point xy = editor.offsetToXY(offset);
        return new Point(xy.x + 2, xy.y + 1);
    }

    private static Color at(BufferedImage image, Point point) { return new Color(image.getRGB(point.x, point.y) & 0xFFFFFF); }

    public void testCardIsDrawnAboveTheInjectedFragmentBackground() {
        EditorEx editor = editor();
        RangeHighlighter card = SqlCardPass.highlighters(editor).get(0);
        BufferedImage image = paint(editor);
        assertEquals(FILL, at(image, inside(editor, SOURCE.indexOf("status from"))));
        Rectangle bounds = SqlCardRenderer.boundsFor(editor, card);
        assertEquals("the padding left of the literal is card too", FILL, at(image, new Point(bounds.x + 2, bounds.y + bounds.height / 2)));
    }

    public void testSelectionStaysVisibleOverTheCard() {
        EditorEx editor = editor();
        int from = SOURCE.indexOf("status from");
        editor.getSelectionModel().setSelection(from, from + "status from".length());
        BufferedImage image = paint(editor);
        assertEquals(SELECTION, at(image, inside(editor, from + 3)));
        assertEquals("unselected SQL still sits on the card", FILL, at(image, inside(editor, SOURCE.indexOf("orders where"))));
    }
}
