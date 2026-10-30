package com.segfault03.ideadb.sql.validate;

import org.jetbrains.annotations.NotNull;

/** Edit distance (insert, delete, replace, swap of neighbours) with an early exit, for "did you mean" suggestions. */
final class SqlEditDistance {
    private SqlEditDistance() {}

    static boolean within(@NotNull String a, @NotNull String b, int limit) { return distance(a, b, limit) <= limit; }

    /** The distance, or {@code limit + 1} when it exceeds the limit. */
    static int distance(@NotNull String a, @NotNull String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] beforePrevious = new int[b.length() + 1];
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMinimum = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    current[j] = Math.min(current[j], beforePrevious[j - 2] + 1);
                }
                rowMinimum = Math.min(rowMinimum, current[j]);
            }
            if (rowMinimum > limit) return limit + 1;
            int[] rotated = beforePrevious; beforePrevious = previous; previous = current; current = rotated;
        }
        return previous[b.length()];
    }
}
