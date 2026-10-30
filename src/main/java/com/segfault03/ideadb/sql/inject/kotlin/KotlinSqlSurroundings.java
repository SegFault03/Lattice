package com.segfault03.ideadb.sql.inject.kotlin;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import com.segfault03.ideadb.sql.inject.SqlMarkers;
import com.segfault03.ideadb.sql.inject.SqlStatementComments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.*;

import java.util.List;

/**
 * What surrounds a Kotlin string expression. Kotlin PSI cannot resolve a call without the analysis API, so a call is
 * recognized by the name written at the call site and an annotation by its short name.
 */
final class KotlinSqlSurroundings {
    private KotlinSqlSurroundings() {}

    static @NotNull Surroundings of(@NotNull KtExpression top) { return cheap(top, true); }

    /** Whether the string is SQL, reading the annotations only when the explicit tier can use them. */
    static boolean shouldInject(@NotNull KtExpression top, @NotNull String text, @NotNull SqlInjectionRules.Tiers tiers) {
        return SqlInjectionRules.shouldInject(cheap(top, tiers.explicit()), text, tiers);
    }

    /** Nothing here resolves: the marker comment is read first, the annotations only when asked for. */
    private static @NotNull Surroundings cheap(@NotNull KtExpression top, boolean annotations) {
        SqlMarkers.Mark mark = SqlStatementComments.markAbove(statementOf(top));
        boolean annotated = false;
        String callee = null;
        boolean nativeQuery = false;
        PsiElement parent = top.getParent();
        if (parent instanceof KtValueArgument argument && argument.getParent() instanceof KtValueArgumentList list) {
            List<KtValueArgument> arguments = list.getArguments();
            boolean first = !arguments.isEmpty() && arguments.get(0) == argument;
            if (list.getParent() instanceof KtCallExpression call) {
                if (first) callee = nameOf(call);
            } else if (list.getParent() instanceof KtAnnotationEntry entry) {
                nativeQuery = isNativeQuery(entry, argument);
            }
        } else if (parent instanceof KtProperty property && property.getInitializer() == top) {
            annotated = annotations && isSqlAnnotated(property);
        } else if (parent instanceof KtParameter parameter && parameter.getDefaultValue() == top) {
            annotated = annotations && isSqlAnnotated(parameter);
        } else if (parent instanceof KtNamedFunction function && function.getBodyExpression() == top) {
            annotated = annotations && isSqlAnnotated(function);
        } else if (parent instanceof KtReturnExpression) {
            KtNamedFunction function = annotations ? PsiTreeUtil.getParentOfType(parent, KtNamedFunction.class, true, KtClass.class) : null;
            annotated = function != null && isSqlAnnotated(function);
        }
        return new Surroundings(mark, annotated, callee, nativeQuery);
    }

    static @NotNull PsiElement statementOf(@NotNull PsiElement expression) {
        return SqlStatementComments.statementOf(expression, parent -> parent instanceof KtBlockExpression || parent instanceof KtClassBody || parent instanceof PsiFile);
    }

    private static @Nullable String nameOf(KtCallExpression call) {
        KtExpression callee = call.getCalleeExpression();
        return callee == null ? null : callee.getText();
    }

    private static boolean isSqlAnnotated(KtAnnotated owner) {
        for (KtAnnotationEntry entry : owner.getAnnotationEntries()) {
            if (entry.getShortName() == null || !SqlMarkers.LANGUAGE_ANNOTATION.equals(entry.getShortName().asString())) continue;
            List<? extends ValueArgument> arguments = entry.getValueArguments();
            if (arguments.isEmpty() || arguments.get(0).getArgumentExpression() == null) continue;
            if (arguments.get(0).getArgumentExpression() instanceof KtStringTemplateExpression value
                    && value.getEntries().length == 1 && SqlMarkers.isSqlLanguage(value.getEntries()[0].getText())) return true;
        }
        return false;
    }

    /** {@code @Query(value = "...", nativeQuery = true)}, {@code @NativeQuery("...")} and {@code @NamedNativeQuery(query = "...")}. */
    private static boolean isNativeQuery(KtAnnotationEntry entry, KtValueArgument argument) {
        String attribute = argument.isNamed() && argument.getArgumentName() != null ? argument.getArgumentName().getAsName().asString() : "value";
        return SqlInjectionRules.isNativeQueryAttribute(entry.getShortName() == null ? null : entry.getShortName().asString(), attribute,
                () -> entry.getValueArguments().stream().anyMatch(other -> other instanceof KtValueArgument named && named.isNamed()
                        && named.getArgumentName() != null && "nativeQuery".equals(named.getArgumentName().getAsName().asString())
                        && named.getArgumentExpression() != null && "true".equals(named.getArgumentExpression().getText())));
    }
}
