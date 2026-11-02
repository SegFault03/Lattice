package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.markup.CustomHighlighterOrder;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Paints a card behind its SQL. It paints after the editor's backgrounds, so it covers the IDE's own injected-fragment
 * background and the caret row, and then paints the selection inside the card again so selected SQL still shows.
 */
final class SqlCardRenderer implements CustomHighlighterRenderer {
    static final SqlCardRenderer INSTANCE = new SqlCardRenderer();

    private SqlCardRenderer() {}

    @Override public @NotNull CustomHighlighterOrder getOrder() { return CustomHighlighterOrder.AFTER_BACKGROUND; }

    @Override public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
        Rectangle bounds = bounds(editor, highlighter);
        if (bounds.isEmpty()) return;
        Color fill = SqlCardColors.fill(editor.getColorsScheme());
        Color border = SqlCardColors.border(editor.getColorsScheme(), Boolean.TRUE.equals(highlighter.getUserData(SqlCardPass.HOVERED)));
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var shape = new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, SqlCardGeometry.ARC, SqlCardGeometry.ARC);
            g2.setColor(fill);
            g2.fill(shape);
            paintSelection(editor, g2, shape, bounds);
            g2.setColor(border);
            g2.draw(shape);
        } finally {
            g2.dispose();
        }
    }

    /** The selection where it overlaps the card, which the card's fill has just covered. */
    private static void paintSelection(Editor editor, Graphics2D g, Shape card, Rectangle bounds) {
        Color selection = editor.getColorsScheme().getColor(EditorColors.SELECTION_BACKGROUND_COLOR);
        if (selection == null || !editor.getSelectionModel().hasSelection(true)) return;
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clip(card);
            clipped.setColor(selection);
            int firstLine = editor.yToVisualLine(bounds.y), lastLine = editor.yToVisualLine(bounds.y + bounds.height - 1);
            for (Caret caret : editor.getCaretModel().getAllCarets()) {
                if (!caret.hasSelection()) continue;
                int start = caret.getSelectionStart(), end = caret.getSelectionEnd();
                for (int line = firstLine; line <= lastLine; line++) {
                    int lineStart = editor.visualPositionToOffset(new VisualPosition(line, 0));
                    int lineEnd = EditorUtil.getVisualLineEndOffset(editor, line);
                    int from = Math.max(start, lineStart), to = Math.min(end, lineEnd);
                    if (from > to || (from == to && end <= lineEnd)) continue;
                    int x0 = editor.offsetToXY(from, false, false).x;
                    // A selection that runs on past the line covers the rest of the card on that line, as the editor does.
                    int x1 = end > lineEnd ? bounds.x + bounds.width : editor.offsetToXY(to, false, true).x;
                    clipped.fillRect(x0, editor.visualLineToY(line), x1 - x0, editor.getLineHeight());
                }
            }
        } finally {
            clipped.dispose();
        }
    }

    /** The card's rectangle in the editor's content component, from each visual line its segments cover. */
    static @NotNull Rectangle bounds(@NotNull Editor editor, @NotNull RangeHighlighter highlighter) {
        List<RangeMarker> segments = highlighter.getUserData(SqlCardPass.SEGMENTS);
        if (segments == null || !highlighter.isValid()) return new Rectangle();
        List<SqlCardGeometry.Extent> extents = new ArrayList<>();
        for (RangeMarker segment : segments) {
            if (!segment.isValid()) continue;
            // Segments follow typing; the highlighter's own range bounds them, so a deletion never stretches a card past its string.
            int start = Math.max(segment.getStartOffset(), highlighter.getStartOffset());
            int end = Math.min(segment.getEndOffset(), highlighter.getEndOffset());
            if (start >= end) continue;
            VisualPosition from = editor.offsetToVisualPosition(start), to = editor.offsetToVisualPosition(end);
            for (int line = from.line; line <= to.line; line++) {
                // Across a soft wrap: a continuation line starts at its wrap indent, and a wrapped line ends before the wrap.
                int x0 = line == from.line ? editor.visualPositionToXY(from).x
                        : editor.offsetToXY(editor.visualPositionToOffset(new VisualPosition(line, 0)), false, false).x;
                int x1 = line == to.line ? editor.visualPositionToXY(to).x
                        : editor.offsetToXY(EditorUtil.getVisualLineEndOffset(editor, line), false, true).x;
                extents.add(new SqlCardGeometry.Extent(x0, x1, editor.visualLineToY(line), editor.getLineHeight()));
            }
        }
        return SqlCardGeometry.bounds(extents);
    }

    @TestOnly
    static @NotNull Rectangle boundsFor(@NotNull Editor editor, @NotNull RangeHighlighter highlighter) { return bounds(editor, highlighter); }
}
