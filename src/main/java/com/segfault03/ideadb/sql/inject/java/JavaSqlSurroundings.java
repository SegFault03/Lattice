package com.segfault03.ideadb.sql.inject.java;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import com.segfault03.ideadb.sql.inject.SqlMarkers;
import com.segfault03.ideadb.sql.inject.SqlStatementComments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** What surrounds a Java string expression: marker comments, {@code @Language}, the call it is passed to, native query annotations. */
final class JavaSqlSurroundings {
    private static final String LANGUAGE = "Language";
    private static final Set<String> NATIVE_QUERY_ATTRIBUTES = Set.of("value", "countQuery", "query");

    private JavaSqlSurroundings() {}

    static @NotNull Surroundings of(@NotNull PsiExpression top) {
        SqlMarkers.Mark mark = SqlStatementComments.markAbove(statementOf(top));
        boolean annotated = false;
        String callee = null;
        boolean nativeQuery = false;
        PsiElement parent = top.getParent();
        if (parent instanceof PsiExpressionList list && list.getParent() instanceof PsiMethodCallExpression call) {
            PsiExpression[] arguments = list.getExpressions();
            int index = java.util.Arrays.asList(arguments).indexOf(top);
            if (index == 0) callee = call.getMethodExpression().getReferenceName();
            annotated = parameterIsSql(call, index);
        } else if (parent instanceof PsiNameValuePair pair) {
            nativeQuery = isNativeQueryAttribute(pair);
        } else if (parent instanceof PsiVariable variable) {
            annotated = isSqlAnnotated(variable);
        } else if (parent instanceof PsiAssignmentExpression assignment && assignment.getRExpression() == top
                && assignment.getLExpression() instanceof PsiReferenceExpression target && target.resolve() instanceof PsiVariable variable) {
            annotated = isSqlAnnotated(variable);
        } else if (parent instanceof PsiReturnStatement) {
            PsiMethod method = PsiTreeUtil.getParentOfType(parent, PsiMethod.class, true, PsiLambdaExpression.class, PsiClass.class);
            annotated = method != null && isSqlAnnotated(method);
        }
        return new Surroundings(mark, annotated, callee, nativeQuery);
    }

    /** The statement, declaration or member the expression belongs to: the thing a marker comment sits above. */
    static @NotNull PsiElement statementOf(@NotNull PsiElement expression) {
        return SqlStatementComments.statementOf(expression, parent -> parent instanceof PsiCodeBlock || parent instanceof PsiClass || parent instanceof PsiFile);
    }

    private static boolean parameterIsSql(PsiMethodCallExpression call, int index) {
        if (index < 0) return false;
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
        if (name == null || !LANGUAGE.equals(name.getReferenceName())) return false;
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String text && SqlMarkers.isSqlLanguage(text);
    }

    /** {@code @Query(value = "...", nativeQuery = true)}, {@code @NativeQuery("...")} and {@code @NamedNativeQuery(query = "...")}. */
    private static boolean isNativeQueryAttribute(PsiNameValuePair pair) {
        String attribute = pair.getName() == null ? "value" : pair.getName();
        if (!NATIVE_QUERY_ATTRIBUTES.contains(attribute)) return false;
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation.class);
        if (annotation == null || annotation.getNameReferenceElement() == null) return false;
        return switch (String.valueOf(annotation.getNameReferenceElement().getReferenceName())) {
            case "NativeQuery", "NamedNativeQuery" -> true;
            case "Query" -> annotation.findAttributeValue("nativeQuery") instanceof PsiLiteralExpression flag && Boolean.TRUE.equals(flag.getValue());
            default -> false;
        };
    }
}
