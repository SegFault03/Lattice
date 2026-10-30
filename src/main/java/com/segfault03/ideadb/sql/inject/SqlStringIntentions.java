package com.segfault03.ideadb.sql.inject;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Intentions that settle by hand whether a string is SQL, by writing a marker comment above its statement: the
 * comment is visible, travels with the code and needs no stored state. Offered inside the string, whether the caret
 * is in the source file or in the injected SQL.
 */
public final class SqlStringIntentions {
    private SqlStringIntentions() {}

    /** A host string and whether Lattice SQL is injected into it right now. */
    private record Target(@NotNull PsiLanguageInjectionHost host, @NotNull PsiFile hostFile, boolean injected, @NotNull SqlHostAdapter adapter) {}

    private static @Nullable Target targetAt(Project project, Editor editor, PsiFile file) {
        if (!SqlEditorSettings.getInstance().isInjectionEffective()) return null;
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(project);
        PsiLanguageInjectionHost host;
        if (manager.isInjectedFragment(file)) {
            if (!file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return null;
            host = manager.getInjectionHost(file);
        } else {
            PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
            host = PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost.class, false);
        }
        if (host == null || !host.isValidHost()) return null;
        SqlHostAdapter adapter = SqlHostAdapter.EXTENSION.forLanguage(host.getLanguage());
        PsiFile hostFile = host.getContainingFile();
        if (adapter == null || hostFile == null || adapter.surroundingsOf(host) == null) return null;
        boolean injected = manager.getInjectedPsiFiles(host) != null && manager.getInjectedPsiFiles(host).stream()
                .anyMatch(pair -> pair.first.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE));
        return new Target(host, hostFile, injected, adapter);
    }

    private static void writeMarker(Project project, Target target, String comment) {
        Document document = PsiDocumentManager.getInstance(project).getDocument(target.hostFile());
        if (document == null) return;
        PsiElement statement = target.adapter().statementOf(target.host());
        int line = document.getLineNumber(statement.getTextRange().getStartOffset());
        int lineStart = document.getLineStartOffset(line);
        CharSequence text = document.getImmutableCharSequence();
        int indentEnd = lineStart;
        while (indentEnd < text.length() && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
        String indent = text.subSequence(lineStart, indentEnd).toString();
        document.insertString(lineStart, indent + "// " + comment + "\n");
        PsiDocumentManager.getInstance(project).commitDocument(document);
    }

    /** The string is SQL to Lattice now; this says it is not. */
    public static final class NotSql implements IntentionAction, com.intellij.openapi.project.DumbAware {
        @Override public @Nls @NotNull String getText() { return "Do not treat this string as SQL"; }
        @Override public @NotNull String getFamilyName() { return "Lattice SQL"; }
        @Override public boolean startInWriteAction() { return true; }

        @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            Target target = editor == null ? null : targetAt(project, editor, file);
            if (target == null || !target.injected()) return false;
            // A string made SQL by the user's own annotation or comment is theirs to change: the marker would not win there.
            var around = target.adapter().surroundingsOf(target.host());
            return around != null && around.mark() != SqlMarkers.Mark.SQL && !around.annotatedSql();
        }

        @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            Target target = targetAt(project, editor, file);
            if (target != null) writeMarker(project, target, SqlMarkers.NOT_SQL_COMMENT);
        }
    }

    /** The string is not SQL to Lattice now; this says it is. */
    public static final class AsSql implements IntentionAction, com.intellij.openapi.project.DumbAware {
        @Override public @Nls @NotNull String getText() { return "Treat this string as SQL"; }
        @Override public @NotNull String getFamilyName() { return "Lattice SQL"; }
        @Override public boolean startInWriteAction() { return true; }

        @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            Target target = editor == null ? null : targetAt(project, editor, file);
            if (target == null || target.injected()) return false;
            return !ElementManipulators.getValueText(target.host()).isBlank();
        }

        @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            Target target = targetAt(project, editor, file);
            if (target != null) writeMarker(project, target, SqlMarkers.SQL_COMMENT);
        }
    }
}
