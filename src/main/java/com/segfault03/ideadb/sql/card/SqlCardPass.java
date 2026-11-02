package com.segfault03.ideadb.sql.card;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Replaces an editor's card highlighters with one per card of the file; a disabled pass only clears them. */
final class SqlCardPass extends TextEditorHighlightingPass {
    static final Key<List<RangeHighlighter>> HIGHLIGHTERS = Key.create("lattice.sql.card.highlighters");
    static final Key<SqlCard> CARD = Key.create("lattice.sql.card");
    static final Key<Boolean> HOVERED = Key.create("lattice.sql.card.hovered");

    private final PsiFile file;
    private final Editor editor;
    private final boolean enabled;
    private List<SqlCard> cards = List.of();

    SqlCardPass(@NotNull PsiFile file, @NotNull Editor editor, boolean enabled) {
        super(file.getProject(), editor.getDocument(), false);
        this.file = file;
        this.editor = editor;
        this.enabled = enabled;
    }

    @Override public void doCollectInformation(@NotNull ProgressIndicator progress) {
        if (enabled) cards = SqlCardLocator.cards(myProject, file);
    }

    @Override public void doApplyInformationToEditor() {
        List<RangeHighlighter> old = editor.getUserData(HIGHLIGHTERS);
        if (old != null) old.forEach(RangeHighlighter::dispose);
        if (cards.isEmpty()) {
            editor.putUserData(HIGHLIGHTERS, null);
            return;
        }
        MarkupModel markup = editor.getMarkupModel();
        int length = editor.getDocument().getTextLength();
        List<RangeHighlighter> added = new ArrayList<>(cards.size());
        for (SqlCard card : cards) {
            if (card.range().getEndOffset() > length) continue;
            RangeHighlighter highlighter = markup.addRangeHighlighter(card.range().getStartOffset(), card.range().getEndOffset(),
                    HighlighterLayer.FIRST - 1, null, HighlighterTargetArea.EXACT_RANGE);
            highlighter.putUserData(CARD, card);
            highlighter.setCustomRenderer(SqlCardRenderer.INSTANCE);
            added.add(highlighter);
        }
        editor.putUserData(HIGHLIGHTERS, added);
    }

    /** The editor's live card highlighters, in document order. */
    static @NotNull List<RangeHighlighter> highlighters(@NotNull Editor editor) {
        List<RangeHighlighter> highlighters = editor.getUserData(HIGHLIGHTERS);
        return highlighters == null ? List.of() : highlighters.stream().filter(RangeHighlighter::isValid).toList();
    }
}
