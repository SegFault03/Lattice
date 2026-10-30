package com.segfault03.ideadb.sql.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the popup without a shortcut where a name is certain to follow: after a dot, and after a space that ends a
 * clause word expecting a table or column (FROM, JOIN, WHERE, SET, ORDER BY...). Letters already open it by default.
 */
public final class LatticeSqlTypedHandler extends TypedHandlerDelegate implements DumbAware {
    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) || !SqlEditorSettings.getInstance().isCompletionEffective()) return Result.CONTINUE;
        if (charTyped == '.' || (charTyped == ' ' && expectsName(project, editor, file))) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }
        return Result.CONTINUE;
    }

    private static boolean expectsName(Project project, Editor editor, PsiFile file) {
        var profile = SqlDialectResolver.resolve(project, file.getVirtualFile());
        int caret = editor.getCaretModel().getOffset();
        SqlContext context = SqlStatementAnalyzer.analyze(editor.getDocument().getImmutableCharSequence(), caret, profile);
        if (!context.prefix().isEmpty() || context.afterOperand()) return false;
        return switch (context.position()) {
            case TABLE_NAME, SET_COLUMN, CONDITION, ORDER_GROUP -> true;
            default -> false;
        };
    }
}
