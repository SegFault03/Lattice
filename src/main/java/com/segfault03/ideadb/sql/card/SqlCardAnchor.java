package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;

/** Where something that stands in for a card goes: the card's painted rectangle, in the editor content component's coordinates. */
public final class SqlCardAnchor {
    private SqlCardAnchor() {}

    /**
     * The rectangle of {@code card} when the editor shows it, else of the card the caret is in, else the caret's line
     * from its first character, for an editor that shows no cards.
     */
    public static @NotNull Rectangle of(@NotNull Editor editor, @Nullable SqlCard card) {
        RangeHighlighter found = null;
        if (card != null) {
            TextRange range = card.range();
            for (RangeHighlighter highlighter : SqlCardPass.highlighters(editor)) {
                if (card.equals(highlighter.getUserData(SqlCardPass.CARD)) || highlighter.getTextRange().equals(range)) {
                    found = highlighter;
                    break;
                }
            }
        }
        if (found == null) {
            int offset = editor.getCaretModel().getOffset();
            for (RangeHighlighter highlighter : SqlCardPass.highlighters(editor)) {
                if (highlighter.getStartOffset() <= offset && offset <= highlighter.getEndOffset()) {
                    found = highlighter;
                    break;
                }
            }
        }
        if (found != null) {
            Rectangle bounds = SqlCardRenderer.bounds(editor, found);
            if (!bounds.isEmpty()) return bounds;
        }
        int line = editor.getCaretModel().getLogicalPosition().line;
        int start = editor.getDocument().getLineStartOffset(line), end = editor.getDocument().getLineEndOffset(line);
        CharSequence text = editor.getDocument().getCharsSequence();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        var point = editor.logicalPositionToXY(editor.offsetToLogicalPosition(start));
        int right = editor.logicalPositionToXY(new LogicalPosition(line, end - editor.getDocument().getLineStartOffset(line))).x;
        return new Rectangle(point.x - SqlCardGeometry.PADDING, point.y, Math.max(0, right - point.x) + 2 * SqlCardGeometry.PADDING, editor.getLineHeight());
    }

    /** How many cards the editor shows now. */
    public static int count(@NotNull Editor editor) { return SqlCardPass.highlighters(editor).size(); }
}
