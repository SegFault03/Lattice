package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    private SqlInjectionRules() {}

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
}
