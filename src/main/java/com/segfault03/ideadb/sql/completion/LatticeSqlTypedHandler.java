package com.segfault03.ideadb.sql.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.binding.SqlEmbeddedBinding;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the popup without a shortcut where a name is certain to follow: after a dot, after a space that ends a
 * clause word expecting a table or column (FROM, JOIN, WHERE, ON, SET, ORDER BY...), and after the "(" or "," of an
 * INSERT column list, SET list or ORDER BY list. Letters already open it by default.
 */
public final class LatticeSqlTypedHandler extends TypedHandlerDelegate implements DumbAware {
    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) || !SqlEditorSettings.getInstance().isCompletionEffective()) return Result.CONTINUE;
        if (charTyped == '.' || ((charTyped == ' ' || charTyped == ',' || charTyped == '(') && expectsName(project, editor, file, charTyped))) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }
        return Result.CONTINUE;
    }

    private static boolean expectsName(Project project, Editor editor, PsiFile file, char charTyped) {
        var profile = SqlDialectResolver.resolve(project, file.getVirtualFile());
        int caret = editor.getCaretModel().getOffset();
        // The platform asks before the character is in the document: analyze the text as it will be once it is.
        CharSequence text = editor.getDocument().getImmutableCharSequence();
        var window = SqlTextWindow.around(text, caret);
        String typed = text.subSequence(window.start(), caret) + String.valueOf(charTyped) + text.subSequence(caret, window.end());
        int typedCaret = caret - window.start() + 1;
        var embedded = SqlEmbeddedBinding.of(file.getVirtualFile());
        if (embedded != null && !embedded.leadIn().isEmpty()) {
            typed = embedded.leadIn() + typed;
            typedCaret += embedded.leadIn().length();
        }
        SqlContext context = SqlStatementAnalyzer.analyze(typed, typedCaret, profile);
        if (!context.prefix().isEmpty() || context.afterOperand()) return false;
        return switch (context.position()) {
            case SET_COLUMN, ORDER_GROUP, INSERT_COLUMNS -> true;
            // a comma or parenthesis there is more often an argument list than a name list
            case TABLE_NAME, CONDITION -> charTyped == ' ';
            default -> false;
        };
    }
}
