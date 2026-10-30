package com.segfault03.ideadb.sql.inject.java;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.segfault03.ideadb.sql.inject.SqlHostInjection;
import com.segfault03.ideadb.sql.inject.SqlInjectionAssembler;
import com.segfault03.ideadb.sql.inject.SqlInjectionAssembler.Part;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes the string literals of a Java expression SQL when the user's settings and the surroundings say they are.
 * A concatenation {@code "select * from t where a = " + a + " and b = ?"} is one injection over all its literals,
 * with a placeholder where {@code a} is. The platform asks about each literal on its own, so every literal registers
 * the same places and finds the injection of the whole expression.
 */
public final class JavaSqlInjector implements MultiHostInjector {
    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof PsiLiteralExpression literal) || !(literal.getValue() instanceof String)) return;
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        if (!settings.isInjectionEffective() || !isHost(literal)) return;

        PsiExpression top = topOfConcatenation(literal);
        List<Part<PsiLanguageInjectionHost>> parts = new ArrayList<>();
        flatten(top, parts);
        var assembled = new SqlInjectionAssembler<>(parts);
        if (!SqlInjectionRules.shouldInject(JavaSqlSurroundings.of(top), assembled.text(), settings.injectionTiers())) return;
        SqlHostInjection.register(registrar, assembled);
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(PsiLiteralExpression.class);
    }

    private static boolean isHost(PsiLiteralExpression literal) {
        return literal instanceof PsiLanguageInjectionHost host && host.isValidHost();
    }

    private static boolean isConcatenation(PsiElement element) {
        return element instanceof PsiPolyadicExpression poly && poly.getOperationTokenType() == JavaTokenType.PLUS;
    }

    static PsiExpression topOfConcatenation(PsiExpression literal) {
        PsiExpression current = literal;
        while (current.getParent() instanceof PsiParenthesizedExpression || isConcatenation(current.getParent())) {
            current = (PsiExpression) current.getParent();
        }
        return current;
    }

    private static void flatten(PsiExpression expression, List<Part<PsiLanguageInjectionHost>> parts) {
        if (expression instanceof PsiParenthesizedExpression parenthesized && parenthesized.getExpression() != null) {
            flatten(parenthesized.getExpression(), parts);
        } else if (isConcatenation(expression)) {
            for (PsiExpression operand : ((PsiPolyadicExpression) expression).getOperands()) flatten(operand, parts);
        } else if (expression instanceof PsiLiteralExpression literal && literal.getValue() instanceof String value && isHost(literal)) {
            var range = ElementManipulators.getValueTextRange(literal);
            parts.add(Part.literal((PsiLanguageInjectionHost) literal, range.getStartOffset(), range.getEndOffset(), value));
        } else {
            parts.add(Part.dynamic());
        }
    }
}
