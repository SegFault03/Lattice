package com.segfault03.ideadb.sql.validate;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code -- noinspection <ShortName>} for Lattice SQL files, see {@link SqlSuppressions} for where the comment may stand.
 * SQL injected into Java or Kotlin strings is suppressed through the host language instead.
 */
public final class LatticeSqlInspectionSuppressor implements InspectionSuppressor {
    private static final Key<CachedValue<SqlSuppressions>> KEY = Key.create("lattice.sql.suppressions");

    /** The suppression comments of a file, or null for SQL inside another language's string. */
    static @Nullable SqlSuppressions of(@NotNull PsiFile file) {
        if (InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)) return null;
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(file, KEY, () -> {
            var profile = SqlDialectResolver.resolve(file.getProject(), file.getOriginalFile().getVirtualFile());
            // the dialect decides which characters start a comment
            return CachedValueProvider.Result.create(SqlSuppressions.of(file.getViewProvider().getContents(), profile), file,
                    SqlBindingsState.getInstance(file.getProject()), SqlEditorSettings.getInstance());
        }, false);
    }

    @Override public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        PsiFile file = element.getContainingFile();
        if (file == null) return false;
        SqlSuppressions suppressions = of(file);
        if (suppressions == null) return false;
        // A finding reported on the whole file is judged by the file's comment only; anything inside it by its statement as well.
        return element instanceof PsiFile ? suppressions.suppressedInFile(toolId)
                : suppressions.isSuppressed(element.getTextRange().getStartOffset(), toolId);
    }

    @Override public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
        return new SuppressQuickFix[] {new SuppressFix(toolId, false), new SuppressFix(toolId, true)};
    }

    /** Adds the comment, or the inspection's name to the comment that is already there. */
    private static final class SuppressFix implements SuppressQuickFix {
        private final String toolId;
        private final boolean wholeFile;

        SuppressFix(String toolId, boolean wholeFile) { this.toolId = toolId; this.wholeFile = wholeFile; }

        @Override public @NotNull String getFamilyName() { return wholeFile ? "Suppress for file" : "Suppress for statement"; }
        @Override public boolean isAvailable(@NotNull Project project, @NotNull PsiElement context) {
            return context.isValid() && context.getContainingFile() != null && of(context.getContainingFile()) != null;
        }
        @Override public boolean isSuppressAll() { return false; }
        @Override public boolean startInWriteAction() { return true; }

        @Override public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            PsiFile file = element.getContainingFile();
            // The intention preview applies the fix to a copy of the file, which the document manager has no document for.
            Document document = file.getViewProvider().getDocument();
            if (document == null) return;
            TextRange inside = descriptor.getTextRangeInElement();
            int offset = element.getTextRange().getStartOffset() + (inside == null ? 0 : inside.getStartOffset());
            var profile = SqlDialectResolver.resolve(project, file.getOriginalFile().getVirtualFile());
            SqlSuppressions suppressions = SqlSuppressions.of(document.getImmutableCharSequence(), profile);
            SqlSuppressions.Edit edit = wholeFile ? suppressions.forFile(toolId) : suppressions.forStatement(offset, toolId);
            if (edit == null) return;
            document.replaceString(edit.range().getStartOffset(), edit.range().getEndOffset(), edit.replacement());
        }
    }
}
