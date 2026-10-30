package com.segfault03.ideadb.sql.inject;

import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.segfault03.ideadb.sql.inject.SqlInjectionAssembler.Part;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The part of injecting SQL into a string expression that does not depend on the language: the decision is made once for
 * the whole expression and cached on it, and the places it yields are registered. The language supplies what only its PSI
 * can: the pieces of the expression, and whether the surroundings and the text make it SQL.
 */
public final class SqlInjectionDecision {
    private SqlInjectionDecision() {}

    /**
     * @param top     the topmost expression the literal belongs to (the whole concatenation)
     * @param flatten adds the expression's literals and unknown parts, in order
     * @param decide  whether the string, given its text with placeholders and the tiers that are on, is SQL
     */
    public static void inject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement top,
                              @NotNull Consumer<List<Part<PsiLanguageInjectionHost>>> flatten,
                              @NotNull BiPredicate<String, SqlInjectionRules.Tiers> decide) {
        // The platform asks once per literal; the whole expression is decided once until the code or the settings change.
        Optional<SqlInjectionAssembler<PsiLanguageInjectionHost>> injection = CachedValuesManager.getCachedValue(top, () -> CachedValueProvider.Result.create(
                decide(flatten, decide), PsiModificationTracker.MODIFICATION_COUNT, SqlEditorSettings.getInstance(),
                DumbService.getInstance(top.getProject()).getModificationTracker()));
        injection.ifPresent(assembled -> SqlHostInjection.register(registrar, assembled));
    }

    private static Optional<SqlInjectionAssembler<PsiLanguageInjectionHost>> decide(Consumer<List<Part<PsiLanguageInjectionHost>>> flatten,
                                                                                    BiPredicate<String, SqlInjectionRules.Tiers> decide) {
        List<Part<PsiLanguageInjectionHost>> parts = new ArrayList<>();
        flatten.accept(parts);
        if (parts.stream().allMatch(Part::isDynamic)) return Optional.empty();
        var assembled = new SqlInjectionAssembler<>(parts);
        return decide.test(assembled.text(), SqlEditorSettings.getInstance().injectionTiers()) ? Optional.of(assembled) : Optional.empty();
    }
}
