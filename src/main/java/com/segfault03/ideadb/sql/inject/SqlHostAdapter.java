package com.segfault03.ideadb.sql.inject;

import com.intellij.lang.LanguageExtension;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the language-neutral parts (the intentions) need to know about a string literal in a language that can carry
 * SQL. Implemented where that language's PSI is available, and registered per language.
 */
public interface SqlHostAdapter {
    LanguageExtension<SqlHostAdapter> EXTENSION = new LanguageExtension<>("com.segfault03.lattice.sqlHostAdapter");

    /** The statement, declaration or member the string belongs to: a marker comment goes on the line above it. */
    @NotNull PsiElement statementOf(@NotNull PsiElement host);

    /** What surrounds the string expression the host is part of, or null when the host is not a plain string. */
    @Nullable SqlInjectionRules.Surroundings surroundingsOf(@NotNull PsiElement host);
}
