package com.segfault03.ideadb.sql.card;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.status.LatticeActivity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings an editor's card highlighters in line with the file's cards; a disabled pass only clears them. A card that is
 * unchanged keeps its highlighter. Each segment has a range marker, so the card follows typing until the next pass,
 * and each highlighter remembers the document stamp its card was read at, so stale SQL is never offered.
 */
final class SqlCardPass extends TextEditorHighlightingPass {
    static final Key<List<RangeHighlighter>> HIGHLIGHTERS = Key.create("lattice.sql.card.highlighters");
    static final Key<SqlCard> CARD = Key.create("lattice.sql.card");
    static final Key<Boolean> HOVERED = Key.create("lattice.sql.card.hovered");
    static final Key<List<RangeMarker>> SEGMENTS = Key.create("lattice.sql.card.segments");
    static final Key<Long> STAMP = Key.create("lattice.sql.card.stamp");

    private final PsiFile file;
    private final Editor editor;
    private final boolean enabled;
    private List<SqlCard> cards = List.of();
    private long stamp = -1;

    SqlCardPass(@NotNull PsiFile file, @NotNull Editor editor, boolean enabled) {
        super(file.getProject(), editor.getDocument(), false);
        this.file = file;
        this.editor = editor;
        this.enabled = enabled;
    }

    @Override public void doCollectInformation(@NotNull ProgressIndicator progress) {
        stamp = editor.getDocument().getModificationStamp();
        if (!enabled) return;
        try (var ignored = LatticeActivity.begin(file.getVirtualFile(), LatticeActivity.Stage.READING)) {
            cards = SqlCardLocator.cards(myProject, file);
        }
    }

    @Override public void doApplyInformationToEditor() {
        Document document = editor.getDocument();
        if (document.getModificationStamp() != stamp) return;
        List<RangeHighlighter> old = new ArrayList<>(highlighters(editor));
        MarkupModel markup = editor.getMarkupModel();
        List<RangeHighlighter> current = new ArrayList<>(cards.size());
        for (SqlCard card : cards) {
            if (card.range().getEndOffset() > document.getTextLength()) continue;
            RangeHighlighter kept = old.stream().filter(highlighter -> card.equals(highlighter.getUserData(CARD))
                    && highlighter.getTextRange().equals(card.range())).findFirst().orElse(null);
            if (kept != null) {
                old.remove(kept);
                kept.putUserData(STAMP, stamp);
                current.add(kept);
                continue;
            }
            RangeHighlighter highlighter = markup.addRangeHighlighter(card.range().getStartOffset(), card.range().getEndOffset(),
                    HighlighterLayer.FIRST - 1, null, HighlighterTargetArea.EXACT_RANGE);
            List<RangeMarker> segments = new ArrayList<>(card.segments().size());
            for (TextRange segment : card.segments()) segments.add(document.createRangeMarker(segment));
            highlighter.putUserData(CARD, card);
            highlighter.putUserData(SEGMENTS, segments);
            highlighter.putUserData(STAMP, stamp);
            highlighter.setCustomRenderer(SqlCardRenderer.INSTANCE);
            current.add(highlighter);
        }
        old.forEach(SqlCardPass::dispose);
        editor.putUserData(HIGHLIGHTERS, current.isEmpty() ? null : current);
        // The toolbar of the card the caret is in was waiting for its card to be read again.
        SqlCardHoverController controller = SqlCardHoverController.of(editor);
        if (controller != null) controller.cardsChanged();
    }

    private static void dispose(RangeHighlighter highlighter) {
        List<RangeMarker> segments = highlighter.getUserData(SEGMENTS);
        if (segments != null) segments.forEach(RangeMarker::dispose);
        highlighter.dispose();
    }

    /** The editor's live card highlighters, in document order. */
    static @NotNull List<RangeHighlighter> highlighters(@NotNull Editor editor) {
        List<RangeHighlighter> highlighters = editor.getUserData(HIGHLIGHTERS);
        return highlighters == null ? List.of() : highlighters.stream().filter(RangeHighlighter::isValid).toList();
    }

    /** Whether the card's SQL was read from the document as it is now. */
    static boolean isFresh(@NotNull Editor editor, @NotNull RangeHighlighter highlighter) {
        Long read = highlighter.getUserData(STAMP);
        return highlighter.isValid() && read != null && read == editor.getDocument().getModificationStamp();
    }
}
