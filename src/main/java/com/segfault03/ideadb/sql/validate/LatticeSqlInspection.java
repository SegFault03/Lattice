package com.segfault03.ideadb.sql.validate;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One kind of finding of {@link SqlScriptValidator} as a local inspection, so its severity, scope and on/off state are
 * the user's to set under Settings | Editor | Inspections | Lattice SQL.
 */
abstract class LatticeSqlInspection extends LocalInspectionTool {
    private final Kind kind;

    LatticeSqlInspection(Kind kind) { this.kind = kind; }

    @Override
    public ProblemDescriptor @Nullable [] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return null;
        List<ProblemDescriptor> problems = new ArrayList<>();
        int length = file.getTextLength();
        for (SqlDiagnostic diagnostic : SqlValidation.diagnostics(file)) {
            if (diagnostic.kind() != kind || diagnostic.end() > length || diagnostic.start() >= diagnostic.end()) continue;
            LocalQuickFix[] fixes = diagnostic.replacement() == null ? LocalQuickFix.EMPTY_ARRAY : new LocalQuickFix[] {new Replace(diagnostic.replacement())};
            problems.add(manager.createProblemDescriptor(file, new TextRange(diagnostic.start(), diagnostic.end()), diagnostic.message(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, fixes));
        }
        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /** Puts the likely intended spelling in place of the flagged name or word. */
    private static final class Replace implements LocalQuickFix {
        private final String replacement;

        Replace(String replacement) { this.replacement = replacement; }

        @Override public @NotNull String getFamilyName() { return "Replace with the suggested name"; }
        @Override public @NotNull String getName() { return "Replace with '" + replacement + "'"; }

        @Override public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            var document = PsiDocumentManager.getInstance(project).getDocument(file);
            TextRange range = descriptor.getTextRangeInElement();
            if (document != null && range != null) document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        }
    }
}
