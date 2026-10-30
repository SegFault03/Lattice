package com.segfault03.ideadb.sql.inject.kotlin;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.segfault03.ideadb.sql.inject.SqlHostInjection;
import com.segfault03.ideadb.sql.inject.SqlInjectionAssembler;
import com.segfault03.ideadb.sql.inject.SqlInjectionAssembler.Part;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Makes the string literals of a Kotlin expression SQL when the user's settings and the surroundings say they are.
 * Plain, raw and template strings work: text between template entries, and between the operands of a {@code +},
 * is unknown and gets a placeholder. {@code trimIndent()} and {@code trimMargin()} on a raw string are looked through.
 */
public final class KotlinSqlInjector implements MultiHostInjector, com.intellij.openapi.project.DumbAware {
    private static final Set<String> STRING_PASSTHROUGH = Set.of("trimIndent", "trimMargin", "trim", "trimStart", "trimEnd", "intern");

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof KtStringTemplateExpression literal) || !literal.isValidHost()) return;
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        if (!settings.isInjectionEffective()) return;

        KtExpression top = topOfConcatenation(literal);
        // The platform asks once per literal; the whole expression is decided once until the code or the settings change.
        Optional<SqlInjectionAssembler<PsiLanguageInjectionHost>> injection = CachedValuesManager.getCachedValue(top, () -> CachedValueProvider.Result.create(
                decide(top), PsiModificationTracker.MODIFICATION_COUNT, SqlEditorSettings.getInstance(), DumbService.getInstance(top.getProject()).getModificationTracker()));
        injection.ifPresent(assembled -> SqlHostInjection.register(registrar, assembled));
    }

    private static Optional<SqlInjectionAssembler<PsiLanguageInjectionHost>> decide(KtExpression top) {
        List<Part<PsiLanguageInjectionHost>> parts = new ArrayList<>();
        flatten(top, parts);
        if (parts.stream().noneMatch(part -> !part.isDynamic())) return Optional.empty();
        var assembled = new SqlInjectionAssembler<>(parts);
        return KotlinSqlSurroundings.shouldInject(top, assembled.text(), SqlEditorSettings.getInstance().injectionTiers()) ? Optional.of(assembled) : Optional.empty();
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(KtStringTemplateExpression.class);
    }

    private static boolean isPlus(PsiElement element) {
        return element instanceof KtBinaryExpression binary && binary.getOperationToken() == KtTokens.PLUS;
    }

    /** {@code "..." .trimIndent()}: the string with a call that only reshapes it. */
    private static boolean isPassthroughCall(PsiElement element, KtExpression receiver) {
        return element instanceof KtDotQualifiedExpression dot && dot.getReceiverExpression() == receiver
                && dot.getSelectorExpression() instanceof KtCallExpression call && call.getCalleeExpression() != null
                && STRING_PASSTHROUGH.contains(call.getCalleeExpression().getText());
    }

    static KtExpression topOfConcatenation(KtExpression literal) {
        KtExpression current = literal;
        while (true) {
            PsiElement parent = current.getParent();
            if (parent instanceof KtParenthesizedExpression || isPlus(parent) || isPassthroughCall(parent, current)) current = (KtExpression) parent;
            else return current;
        }
    }

    private static void flatten(@Nullable KtExpression expression, List<Part<PsiLanguageInjectionHost>> parts) {
        if (expression instanceof KtParenthesizedExpression parenthesized && parenthesized.getExpression() != null) {
            flatten(parenthesized.getExpression(), parts);
        } else if (isPlus(expression)) {
            KtBinaryExpression binary = (KtBinaryExpression) expression;
            flatten(binary.getLeft(), parts);
            flatten(binary.getRight(), parts);
        } else if (expression instanceof KtDotQualifiedExpression dot && isPassthroughCall(dot, dot.getReceiverExpression())) {
            flatten(dot.getReceiverExpression(), parts);
        } else if (expression instanceof KtStringTemplateExpression template && template.isValidHost()) {
            flattenTemplate(template, parts);
        } else {
            parts.add(Part.dynamic());
        }
    }

    /** Runs of literal and escape entries are places; every template entry between them is an unknown part. */
    private static void flattenTemplate(KtStringTemplateExpression template, List<Part<PsiLanguageInjectionHost>> parts) {
        int start = -1, end = -1;
        StringBuilder text = new StringBuilder();
        for (KtStringTemplateEntry entry : template.getEntries()) {
            if (entry instanceof KtLiteralStringTemplateEntry || entry instanceof KtEscapeStringTemplateEntry) {
                if (start < 0) start = entry.getStartOffsetInParent();
                end = entry.getStartOffsetInParent() + entry.getTextLength();
                text.append(entry instanceof KtEscapeStringTemplateEntry escape ? escape.getUnescapedValue() : entry.getText());
            } else {
                if (start >= 0) parts.add(Part.literal(template, start, end, text.toString()));
                start = -1;
                text.setLength(0);
                parts.add(Part.dynamic());
            }
        }
        if (start >= 0) parts.add(Part.literal(template, start, end, text.toString()));
    }
}
