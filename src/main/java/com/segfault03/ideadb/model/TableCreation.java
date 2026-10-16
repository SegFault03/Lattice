package com.segfault03.ideadb.model;

import java.util.*;

/** Immutable definition shared by the creation preview and its background execution. */
public record TableCreation(String name, List<Column> columns) {
    public TableCreation { name = name.trim(); columns = List.copyOf(columns); }

    public record Column(String name, String type, int size, int scale, boolean nullable,
                         boolean primaryKey, boolean autoIncrement, String defaultValue) {
        public static Column parse(String name, String type, String length, boolean nullable,
                                   boolean primaryKey, boolean autoIncrement, String defaultValue) {
            var parsed = TableAlteration.column(TableAlteration.Kind.ADD, null, name, type, length, nullable, defaultValue);
            return new Column(parsed.name(), parsed.type(), parsed.size(), parsed.scale(), nullable, primaryKey, autoIncrement, defaultValue);
        }
        public ColumnDefinition definition() {
            var definition = new ColumnDefinition(name, type, size, nullable, primaryKey, autoIncrement, defaultValue);
            definition.setDecimalDigits(scale); return definition;
        }
    }

    public static boolean identityType(DatabaseType engine, String type) {
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return engine == DatabaseType.POSTGRESQL
                ? Set.of("INT", "INTEGER", "BIGINT", "SERIAL", "BIGSERIAL").contains(normalized)
                : Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT").contains(normalized);
    }

    public String problem(DatabaseType engine) {
        if (name.isBlank()) return "Enter a table name.";
        if (name.indexOf('\0') >= 0) return "Table names cannot contain a null character.";
        if (columns.isEmpty()) return "Add at least one column.";
        Set<String> names = new HashSet<>();
        long identities = columns.stream().filter(Column::autoIncrement).count();
        if ((engine == DatabaseType.MYSQL || engine == DatabaseType.HSQLDB) && identities > 1)
            return "This database supports one auto increment column per table.";
        Column firstKey = columns.stream().filter(Column::primaryKey).findFirst().orElse(null);
        for (Column column : columns) {
            var check = new TableAlteration(TableAlteration.Kind.ADD, null, column.name, column.type,
                    column.size, column.scale, column.nullable, column.defaultValue);
            String problem = check.problem(List.of(), name);
            if (problem != null) return column.name.isBlank() ? problem : column.name + " · " + problem;
            if (!names.add(column.name.toLowerCase(Locale.ROOT))) return "A column named '" + column.name + "' already exists.";
            if (TableAlteration.usesLength(column.type) && (column.size < 1 || column.scale < 0
                    || TableAlteration.numeric(column.type) && column.scale > column.size))
                return column.name + " · Enter a positive length, or valid precision and scale.";
            if ((column.primaryKey || column.autoIncrement) && column.nullable)
                return column.name + " · A primary key or auto increment column cannot allow NULL.";
            if (column.autoIncrement) {
                if (!identityType(engine, column.type)) return column.name + " · Auto increment requires a supported integer type.";
                if (!column.defaultValue.isBlank()) return column.name + " · An auto increment column cannot have another default.";
                if (engine == DatabaseType.MYSQL && (!column.primaryKey || column != firstKey))
                    return column.name + " · MySQL requires auto increment to be the first column in the primary key.";
            }
        }
        return null;
    }

    /** Each caller receives fresh mutable JDBC definitions, never editor-owned state. */
    public List<ColumnDefinition> definitions() { return columns.stream().map(Column::definition).toList(); }
}
