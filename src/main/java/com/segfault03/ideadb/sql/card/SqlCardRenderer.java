package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.VisualPosition;
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
 * Paints a card behind its SQL. It paints before the editor's backgrounds, so the selection and the caret row stay
 * visible over it; on the caret's line the caret-row colour therefore stands in for the card's fill.
 */
final class SqlCardRenderer implements CustomHighlighterRenderer {
    static final SqlCardRenderer INSTANCE = new SqlCardRenderer();

    private SqlCardRenderer() {}

    @Override public @NotNull CustomHighlighterOrder getOrder() { return CustomHighlighterOrder.BEFORE_BACKGROUND; }

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
            g2.setColor(border);
            g2.draw(shape);
        } finally {
            g2.dispose();
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
