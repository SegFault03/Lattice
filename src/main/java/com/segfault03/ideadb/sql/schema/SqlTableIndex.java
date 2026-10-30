package com.segfault03.ideadb.sql.schema;

import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tables of one namespace by name, so a lookup does not scan the list: exact names, names folded to one case, and a
 * sorted list of lowercase names to find the closest one within a few edits without comparing against every table.
 * Immutable once built.
 */
public final class SqlTableIndex {
    private final Map<String, ExplorerMetadataSnapshot.Table> exact = new HashMap<>();
    private final Map<String, List<ExplorerMetadataSnapshot.Table>> folded = new HashMap<>();
    private final List<ExplorerMetadataSnapshot.Table> tables;
    /** Lowercase names in sorted order, and the position in {@link #tables} of each. */
    private final String[] sortedNames;
    private final int[] sortedPositions;

    SqlTableIndex(@NotNull List<ExplorerMetadataSnapshot.Table> tables) {
        this.tables = tables;
        Integer[] order = new Integer[tables.size()];
        String[] lower = new String[tables.size()];
        for (int i = 0; i < order.length; i++) {
            String name = tables.get(i).metadata().getName();
            exact.putIfAbsent(name, tables.get(i));
            folded.computeIfAbsent(fold(name), key -> new ArrayList<>(1)).add(tables.get(i));
            lower[i] = name.toLowerCase(Locale.ROOT);
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> lower[a].equals(lower[b]) ? Integer.compare(a, b) : lower[a].compareTo(lower[b]));
        sortedNames = new String[order.length];
        sortedPositions = new int[order.length];
        for (int i = 0; i < order.length; i++) { sortedNames[i] = lower[order[i]]; sortedPositions[i] = order[i]; }
    }

    /** What {@link String#equalsIgnoreCase} compares: two names are equal ignoring case exactly when their folds are equal. */
    private static String fold(String name) {
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(Character.toUpperCase(chars[i]));
        return new String(chars);
    }

    /** The table of exactly this name, else the only one that matches ignoring case, else null. */
    public @Nullable ExplorerMetadataSnapshot.Table find(@NotNull String name) {
        var match = exact.get(name);
        if (match != null) return match;
        var candidates = folded.get(fold(name));
        return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** Whether more than one table has this name when case is ignored. */
    public boolean ambiguousCase(@NotNull String name) {
        var candidates = folded.get(fold(name));
        return candidates != null && candidates.size() > 1;
    }

    /**
     * The name of the table closest to {@code wanted}, within {@code limit} edits (insert, delete, replace, swap of
     * neighbours) and not the same name ignoring case; of equally close names the one listed first. Null when none is.
     */
    public @Nullable String nearest(@NotNull String wanted, int limit) {
        String target = wanted.toLowerCase(Locale.ROOT);
        int[] first = new int[target.length() + 1];
        for (int j = 0; j < first.length; j++) first[j] = j;
        Search search = new Search(wanted, target, limit);
        search.walk(0, sortedNames.length, 0, null, first, (char) 0);
        return search.bestPosition < 0 ? null : tables.get(search.bestPosition).metadata().getName();
    }

    /** A walk down the sorted names as if they were a trie, carrying one row of the edit distance table per level. */
    private final class Search {
        final String wanted;
        final String target;
        final int limit;
        int bestDistance = Integer.MAX_VALUE;
        int bestPosition = -1;

        Search(String wanted, String target, int limit) { this.wanted = wanted; this.target = target; this.limit = limit; }

        void walk(int from, int to, int depth, int @Nullable [] beforePrevious, int[] previous, char last) {
            int at = from;
            // names that end here come first
            for (; at < to && sortedNames[at].length() == depth; at++) {
                int distance = previous[target.length()];
                int position = sortedPositions[at];
                if (distance > limit || tables.get(position).metadata().getName().equalsIgnoreCase(wanted)) continue;
                if (distance < bestDistance || distance == bestDistance && position < bestPosition) { bestDistance = distance; bestPosition = position; }
            }
            while (at < to) {
                char c = sortedNames[at].charAt(depth);
                int end = at + 1;
                int high = to;
                // the first name past this group has a larger character at this depth
                while (end < high) {
                    int middle = (end + high) >>> 1;
                    if (sortedNames[middle].charAt(depth) == c) end = middle + 1; else high = middle;
                }
                int[] row = new int[previous.length];
                row[0] = depth + 1;
                int minimum = row[0];
                for (int j = 1; j < row.length; j++) {
                    int cost = c == target.charAt(j - 1) ? 0 : 1;
                    row[j] = Math.min(Math.min(row[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                    if (beforePrevious != null && j > 1 && c == target.charAt(j - 2) && last == target.charAt(j - 1)) row[j] = Math.min(row[j], beforePrevious[j - 2] + 1);
                    minimum = Math.min(minimum, row[j]);
                }
                if (minimum <= limit) walk(at, end, depth + 1, previous, row, c);
                at = end;
            }
        }
    }
}
