package com.segfault03.ideadb.model;

import java.util.*;

/** A complete editor snapshot; validation and ordering never contact the database. */
public record TableAlterationBatch(String tableName, List<ColumnMetadata> baseline, List<TableAlteration> changes) {
    public TableAlterationBatch {
        baseline = List.copyOf(baseline);
        changes = List.copyOf(changes);
    }

    public String targetTableName() {
        return changes.stream().filter(c -> c.kind() == TableAlteration.Kind.RENAME_TABLE)
                .map(TableAlteration::name).findFirst().orElse(tableName);
    }

    public String problem() {
        Set<String> names = new HashSet<>();
        Map<String, String> renamed = new HashMap<>();
        Set<String> dropped = new HashSet<>();
        Set<String> operations = new HashSet<>();
        for (TableAlteration change : changes) {
            String problem = change.problem(List.of(), tableName);
            if (problem != null) return problem;
            String original = change.original() == null ? "" : change.original().getName();
            if (!operations.add(change.kind() + ":" + (change.kind() == TableAlteration.Kind.ADD ? change.name() : original)))
                return "The same operation appears more than once.";
            if (change.original() != null && baseline.stream().noneMatch(c -> c.getName().equals(original)))
                return "Column '" + original + "' is missing. Reload the table before retrying.";
            if (change.kind() == TableAlteration.Kind.DROP) dropped.add(original);
            if (change.kind() == TableAlteration.Kind.RENAME_COLUMN) renamed.put(original, change.name());
        }
        for (ColumnMetadata column : baseline) {
            if (dropped.contains(column.getName())) continue;
            String name = renamed.getOrDefault(column.getName(), column.getName());
            if (!names.add(key(name))) return "More than one column is named '" + name + "'.";
        }
        for (TableAlteration change : changes) {
            if (change.kind() == TableAlteration.Kind.ADD && !names.add(key(change.name())))
                return "A column named '" + change.name() + "' already exists.";
            if (change.original() != null && dropped.contains(change.original().getName()) && change.kind() != TableAlteration.Kind.DROP)
                return "A dropped column cannot also be edited.";
        }
        try { orderedChanges(); } catch (IllegalArgumentException error) { return error.getMessage(); }
        return null;
    }

    /** Free names first; modify original definitions before renaming; rename the table last. */
    public List<TableAlteration> orderedChanges() {
        List<TableAlteration> ordered = new ArrayList<>();
        for (var kind : List.of(TableAlteration.Kind.DROP, TableAlteration.Kind.MODIFY))
            changes.stream().filter(c -> c.kind() == kind).forEach(ordered::add);
        List<TableAlteration> renames = new ArrayList<>(changes.stream().filter(c -> c.kind() == TableAlteration.Kind.RENAME_COLUMN).toList());
        while (!renames.isEmpty()) {
            TableAlteration next = renames.stream().filter(candidate -> renames.stream().noneMatch(other ->
                    other != candidate && key(other.original().getName()).equals(key(candidate.name())))).findFirst().orElse(null);
            if (next == null) throw new IllegalArgumentException("These renames form a cycle. Apply an intermediate column name first.");
            ordered.add(next); renames.remove(next);
        }
        for (var kind : List.of(TableAlteration.Kind.ADD, TableAlteration.Kind.RENAME_TABLE))
            changes.stream().filter(c -> c.kind() == kind).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
