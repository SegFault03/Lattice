package com.segfault03.ideadb.sql.inject.kotlin;

import com.intellij.psi.PsiElement;
import com.segfault03.ideadb.sql.inject.SqlHostAdapter;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

public final class KotlinSqlHostAdapter implements SqlHostAdapter {
    @Override public @NotNull PsiElement statementOf(@NotNull PsiElement host) { return KotlinSqlSurroundings.statementOf(host); }

    @Override public @Nullable Surroundings surroundingsOf(@NotNull PsiElement host) {
        return host instanceof KtStringTemplateExpression template
                ? KotlinSqlSurroundings.of(KotlinSqlInjector.topOfConcatenation(template)) : null;
    }
}
