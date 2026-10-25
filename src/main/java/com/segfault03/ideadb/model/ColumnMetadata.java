package com.segfault03.ideadb.model;

public class ColumnMetadata {
    private final String name;
    private final String typeName;
    private final int dataType;
    private final int columnSize;
    private final int decimalDigits;
    private final boolean nullable;
    private final boolean primaryKey;
    private final boolean autoIncrement;
    private final String defaultValue;
    private final boolean generated;
    private final boolean foreignKey;

    public ColumnMetadata(String name, String typeName, int dataType, int columnSize, int decimalDigits,
                          boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue) {
        this(name, typeName, dataType, columnSize, decimalDigits, nullable, primaryKey, autoIncrement, defaultValue, false);
    }
    public ColumnMetadata(String name, String typeName, int dataType, int columnSize, int decimalDigits,
                          boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue, boolean generated) {
        this(name, typeName, dataType, columnSize, decimalDigits, nullable, primaryKey, autoIncrement, defaultValue, generated, false);
    }
    private ColumnMetadata(String name, String typeName, int dataType, int columnSize, int decimalDigits,
                           boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue,
                           boolean generated, boolean foreignKey) {
        this.foreignKey = foreignKey;
        this.generated = generated;
        this.name = name;
        this.typeName = typeName;
        this.dataType = dataType;
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.autoIncrement = autoIncrement;
        this.defaultValue = defaultValue;
    }

    public boolean isForeignKey() { return foreignKey; }
    public ColumnMetadata withForeignKey(boolean foreignKey) {
        return new ColumnMetadata(name, typeName, dataType, columnSize, decimalDigits, nullable,
                primaryKey, autoIncrement, defaultValue, generated, foreignKey);
    }

    public boolean isGenerated() { return generated; }
    public String getName() { return name; }
    public String getTypeName() { return typeName; }
    public int getDataType() { return dataType; }
    public int getColumnSize() { return columnSize; }
    public int getDecimalDigits() { return decimalDigits; }
    public boolean isNullable() { return nullable; }
    public boolean isPrimaryKey() { return primaryKey; }
    public boolean isAutoIncrement() { return autoIncrement; }
    public String getDefaultValue() { return defaultValue; }

    public String getFormattedType() {
        if (typeName.contains("(") || typeName.equalsIgnoreCase("BOOLEAN") || typeName.equalsIgnoreCase("BOOL")) return typeName;
        boolean sized = switch (dataType) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.NCHAR, java.sql.Types.NVARCHAR,
                 java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.BIT,
                 java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> true;
            default -> false;
        };
        if (!sized || columnSize <= 0) return typeName;
        if ((dataType == java.sql.Types.NUMERIC || dataType == java.sql.Types.DECIMAL) && decimalDigits > 0)
            return typeName + "(" + columnSize + "," + decimalDigits + ")";
        return typeName + "(" + columnSize + ")";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        sb.append(" : ").append(getFormattedType());
        if (primaryKey) {
            sb.append(" [PK]");
        }
        if (!nullable) {
            sb.append(" [NOT NULL]");
        }
        if (autoIncrement) {
            sb.append(" [Auto]");
        }
        return sb.toString();
    }
}
