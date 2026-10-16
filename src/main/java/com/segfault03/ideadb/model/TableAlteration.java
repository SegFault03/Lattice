package com.segfault03.ideadb.model;

import com.segfault03.ideadb.provider.DatabaseFeature;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable editor snapshot, shared by preview and execution. */
public record TableAlteration(Kind kind, ColumnMetadata original, String name, String type,
                              int size, int scale, boolean nullable, String defaultValue) {
    public enum Kind { ADD, MODIFY, RENAME_COLUMN, DROP, RENAME_TABLE }

    public static TableAlteration column(Kind kind, ColumnMetadata original, String name, String type,
                                        String length, boolean nullable, String defaultValue) {
        String cleanType = type == null ? "" : type.trim();
        int size = 0, scale = original == null ? 0 : original.getDecimalDigits();
        boolean unbounded = original != null && original.getColumnSize() == 0 && cleanType.equalsIgnoreCase(original.getTypeName()) && length.isBlank();
        if ((kind == Kind.ADD || kind == Kind.MODIFY) && usesLength(cleanType) && !unbounded) {
            if (length == null || !length.trim().matches("\\d+\\s*(,\\s*\\d+)?"))
                throw new IllegalArgumentException("Enter a positive length, or precision and scale (for example 10, 2).");
            String[] parts = length.trim().split(",");
            try {
                size = Integer.parseInt(parts[0].trim());
                if (parts.length == 2) {
                    if (!numeric(cleanType)) throw new IllegalArgumentException("Scale is only available for DECIMAL or NUMERIC.");
                    scale = Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException error) { throw new IllegalArgumentException("Length is too large."); }
            if (size < 1 || scale < 0 || numeric(cleanType) && scale > size)
                throw new IllegalArgumentException("Length must be positive and scale cannot exceed precision.");
        }
        return new TableAlteration(kind, original, name.trim(), cleanType, size, scale, nullable, defaultValue);
    }
    public static boolean numeric(String type) { return type.equalsIgnoreCase("DECIMAL") || type.equalsIgnoreCase("NUMERIC"); }
    public static boolean usesLength(String type) {
        String upper = type.toUpperCase(Locale.ROOT);
        return !upper.contains("(") && (numeric(type) || upper.contains("CHAR") || upper.contains("BINARY"));
    }
    public DatabaseFeature feature() {
        return switch (kind) {
            case ADD -> DatabaseFeature.ADD_COLUMN;
            case MODIFY -> DatabaseFeature.MODIFY_COLUMN;
            case RENAME_COLUMN -> DatabaseFeature.RENAME_COLUMN;
            case DROP -> DatabaseFeature.DROP_COLUMN;
            case RENAME_TABLE -> DatabaseFeature.RENAME_TABLE;
        };
    }
    public String problem(List<ColumnMetadata> columns, String tableName) {
        if (name.isBlank()) return kind == Kind.RENAME_TABLE ? "Enter a table name." : "Enter a column name.";
        if (name.indexOf('\0') >= 0) return "Names cannot contain a null character.";
        if (kind != Kind.ADD && kind != Kind.RENAME_TABLE && original == null) return "Select a column.";
        if ((kind == Kind.ADD || kind == Kind.RENAME_COLUMN) && columns.stream().anyMatch(c ->
                (original == null || !c.getName().equals(original.getName())) && c.getName().equalsIgnoreCase(name)))
            return "A column named '" + name + "' already exists.";
        if (kind == Kind.ADD || kind == Kind.MODIFY) {
            if (type.isBlank()) return "Choose a data type.";
            if (type.contains(";") || type.contains("--") || type.contains("/*") || type.chars().anyMatch(Character::isISOControl))
                return "Enter a single data type.";
            if (kind == Kind.MODIFY && original.isGenerated()) return "Edit generated expressions in the SQL console.";
            if (original != null && original.isPrimaryKey() && nullable) return "A primary-key column cannot allow NULL.";
            if (original != null && original.isAutoIncrement() && !defaultValue.isBlank()
                    && !Objects.equals(defaultValue, original.getDefaultValue())) return "An identity column cannot have another default.";
        }
        return null;
    }
    public boolean changed(String tableName) {
        return switch (kind) {
            case ADD -> !name.isBlank();
            case DROP -> original != null;
            case RENAME_COLUMN -> original != null && !name.equals(original.getName());
            case RENAME_TABLE -> !name.equals(tableName);
            case MODIFY -> original != null && (!type.equalsIgnoreCase(original.getTypeName())
                    || usesLength(type) && (size != original.getColumnSize() || numeric(type) && scale != original.getDecimalDigits())
                    || nullable != original.isNullable() || !Objects.equals(defaultValue, Objects.toString(original.getDefaultValue(), "")));
        };
    }
    public ColumnDefinition definition() {
        String effectiveType = type;
        if (original != null && type.equalsIgnoreCase(original.getTypeName()) && !type.contains("(")
                && type.toUpperCase(Locale.ROOT).matches("(?:TIMESTAMP|TIME)(?: WITH(?:OUT)? TIME ZONE)?"))
            effectiveType = type.replaceFirst("(?i)^(TIMESTAMP|TIME)", "$1(" + original.getDecimalDigits() + ")");
        ColumnDefinition column = new ColumnDefinition(kind == Kind.MODIFY ? original.getName() : name, effectiveType, size,
                nullable, original != null && original.isPrimaryKey(), original != null && original.isAutoIncrement(), defaultValue);
        column.setDecimalDigits(scale);
        if (original != null && original.getDefaultValue() != null && Objects.equals(defaultValue, original.getDefaultValue()))
            column.setDefaultKind(ColumnDefinition.DefaultKind.PRESERVED);
        return column;
    }
}
