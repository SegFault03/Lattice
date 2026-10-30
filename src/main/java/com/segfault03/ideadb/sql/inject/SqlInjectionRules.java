package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Whether a string becomes SQL, from what surrounds it and what it says. The tiers are independent switches:
 * an explicit statement by the user (annotation or comment), a call known to take SQL, and the content itself.
 */
public final class SqlInjectionRules {
    /** What the language adapter found around the string. */
    public record Surroundings(@NotNull SqlMarkers.Mark mark, boolean annotatedSql, @Nullable String callee, boolean nativeQuery) {
        public static Surroundings none() { return new Surroundings(SqlMarkers.Mark.NONE, false, null, false); }
    }

    /** Which tiers are on. */
    public record Tiers(boolean explicit, boolean knownCalls, boolean content, @NotNull SqlKnownCalls calls) {}

    private static final Set<String> NATIVE_QUERY_ATTRIBUTES = Set.of("value", "countQuery", "query");

    private SqlInjectionRules() {}

    /**
     * Whether an annotation attribute holds a native query: {@code @Query(value = "...", nativeQuery = true)},
     * {@code @NativeQuery("...")} and {@code @NamedNativeQuery(query = "...")}.
     *
     * @param annotation       the annotation's short name, null when it has none
     * @param attribute        the attribute the string is the value of ({@code value} when unnamed)
     * @param nativeQueryIsSet whether the annotation sets {@code nativeQuery = true}, asked only for {@code @Query}
     */
    public static boolean isNativeQueryAttribute(@Nullable String annotation, @NotNull String attribute, @NotNull BooleanSupplier nativeQueryIsSet) {
        if (annotation == null || !NATIVE_QUERY_ATTRIBUTES.contains(attribute)) return false;
        return switch (annotation) {
            case "NativeQuery", "NamedNativeQuery" -> true;
            case "Query" -> nativeQueryIsSet.getAsBoolean();
            default -> false;
        };
    }

    /** @param text the whole string, dynamic parts as placeholders */
    public static boolean shouldInject(@NotNull Surroundings around, @NotNull String text, @NotNull Tiers tiers) {
        if (text.isBlank() || around.mark() == SqlMarkers.Mark.NOT_SQL) return false;
        if (tiers.explicit() && (around.mark() == SqlMarkers.Mark.SQL || around.annotatedSql())) return true;
        if (tiers.knownCalls()) {
            if (around.nativeQuery()) return true;
            String callee = around.callee();
            if (callee != null && tiers.calls().contains(callee)) {
                return !SqlKnownCalls.isAmbiguous(callee) || SqlStringHeuristic.startsLikeStatement(text);
            }
        }
        return tiers.content() && SqlStringHeuristic.looksLikeSql(text);
    }

    /**
     * The same decision, for surroundings read without resolving anything. {@code resolvedAnnotation} (finding an annotation by
     * resolving a call or a reference) is asked only when the explicit tier is on and nothing cheaper has decided.
     */
    public static boolean shouldInject(@NotNull Surroundings cheap, @NotNull BooleanSupplier resolvedAnnotation, @NotNull String text, @NotNull Tiers tiers) {
        if (shouldInject(cheap, text, tiers)) return true;
        if (!tiers.explicit() || text.isBlank() || cheap.mark() == SqlMarkers.Mark.NOT_SQL) return false;
        return resolvedAnnotation.getAsBoolean();
    }
}
