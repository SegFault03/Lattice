package com.segfault03.ideadb.sql.inject.java;

import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import com.segfault03.ideadb.sql.inject.SqlMarkers;
import com.segfault03.ideadb.sql.inject.SqlStatementComments;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/** What surrounds a Java string expression: marker comments, {@code @Language}, the call it is passed to, native query annotations. */
final class JavaSqlSurroundings {
    private JavaSqlSurroundings() {}

    /** Everything, including what needs resolving. */
    static @NotNull Surroundings of(@NotNull PsiExpression top) {
        Surroundings cheap = cheap(top, true);
        if (cheap.annotatedSql() || !resolvedAnnotation(top)) return cheap;
        return new Surroundings(cheap.mark(), true, cheap.callee(), cheap.nativeQuery());
    }

    /** Whether the string is SQL, asking for what needs resolving only when nothing cheaper has decided. */
    static boolean shouldInject(@NotNull PsiExpression top, @NotNull String text, @NotNull SqlInjectionRules.Tiers tiers) {
        return SqlInjectionRules.shouldInject(cheap(top, tiers.explicit()), () -> resolvedAnnotation(top), text, tiers);
    }

    /** The marker comment, the written name of the call and the annotations found by looking at the syntax alone. */
    private static @NotNull Surroundings cheap(@NotNull PsiExpression top, boolean annotations) {
        SqlMarkers.Mark mark = SqlStatementComments.markAbove(statementOf(top));
        boolean annotated = false;
        String callee = null;
        boolean nativeQuery = false;
        PsiElement parent = top.getParent();
        if (parent instanceof PsiExpressionList list && list.getParent() instanceof PsiMethodCallExpression call) {
            if (list.getExpressions().length > 0 && list.getExpressions()[0] == top) callee = call.getMethodExpression().getReferenceName();
        } else if (parent instanceof PsiNameValuePair pair) {
            nativeQuery = isNativeQueryAttribute(pair);
        } else if (parent instanceof PsiVariable variable) {
            annotated = annotations && isSqlAnnotated(variable);
        } else if (parent instanceof PsiReturnStatement) {
            PsiMethod method = annotations ? PsiTreeUtil.getParentOfType(parent, PsiMethod.class, true, PsiLambdaExpression.class, PsiClass.class) : null;
            annotated = method != null && isSqlAnnotated(method);
        }
        return new Surroundings(mark, annotated, callee, nativeQuery);
    }

    /** Whether the parameter the string is passed to, or the variable it is assigned to, is annotated; both need resolving. */
    private static boolean resolvedAnnotation(@NotNull PsiExpression top) {
        PsiElement parent = top.getParent();
        if (parent instanceof PsiExpressionList list && list.getParent() instanceof PsiMethodCallExpression call) {
            return parameterIsSql(call, Arrays.asList(list.getExpressions()).indexOf(top));
        }
        return parent instanceof PsiAssignmentExpression assignment && assignment.getRExpression() == top
                && assignment.getLExpression() instanceof PsiReferenceExpression target && !DumbService.isDumb(target.getProject())
                && target.resolve() instanceof PsiVariable variable && isSqlAnnotated(variable);
    }

    /** The statement, declaration or member the expression belongs to: the thing a marker comment sits above. */
    static @NotNull PsiElement statementOf(@NotNull PsiElement expression) {
        return SqlStatementComments.statementOf(expression, parent -> parent instanceof PsiCodeBlock || parent instanceof PsiClass || parent instanceof PsiFile);
    }

    private static boolean parameterIsSql(PsiMethodCallExpression call, int index) {
        // Resolving a call needs the indexes, which are not there while the project is indexing: go by the written name only.
        if (index < 0 || DumbService.isDumb(call.getProject())) return false;
        PsiMethod method = call.resolveMethod();
        if (method == null) return false;
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) return false;
        PsiParameter parameter = parameters[Math.min(index, parameters.length - 1)];
        return isSqlAnnotated(parameter);
    }

    private static boolean isSqlAnnotated(@NotNull PsiModifierListOwner owner) {
        PsiModifierList modifiers = owner.getModifierList();
        if (modifiers == null) return false;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            if (isLanguageSql(annotation)) return true;
        }
        return false;
    }

    private static boolean isLanguageSql(PsiAnnotation annotation) {
        PsiJavaCodeReferenceElement name = annotation.getNameReferenceElement();
        if (name == null || !SqlMarkers.LANGUAGE_ANNOTATION.equals(name.getReferenceName())) return false;
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String text && SqlMarkers.isSqlLanguage(text);
    }

    /** {@code @Query(value = "...", nativeQuery = true)}, {@code @NativeQuery("...")} and {@code @NamedNativeQuery(query = "...")}. */
    private static boolean isNativeQueryAttribute(PsiNameValuePair pair) {
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation.class);
        if (annotation == null || annotation.getNameReferenceElement() == null) return false;
        return SqlInjectionRules.isNativeQueryAttribute(annotation.getNameReferenceElement().getReferenceName(), pair.getName() == null ? "value" : pair.getName(),
                () -> annotation.findAttributeValue("nativeQuery") instanceof PsiLiteralExpression flag && Boolean.TRUE.equals(flag.getValue()));
    }
}
