package com.segfault03.ideadb.sql.inject.java;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.segfault03.ideadb.sql.inject.SqlHostAdapter;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class JavaSqlHostAdapter implements SqlHostAdapter {
    @Override public @NotNull PsiElement statementOf(@NotNull PsiElement host) { return JavaSqlSurroundings.statementOf(host); }

    @Override public @Nullable Surroundings surroundingsOf(@NotNull PsiElement host) {
        return host instanceof PsiLiteralExpression literal && literal.getValue() instanceof String
                ? JavaSqlSurroundings.of(JavaSqlInjector.topOfConcatenation(literal)) : null;
    }
}
