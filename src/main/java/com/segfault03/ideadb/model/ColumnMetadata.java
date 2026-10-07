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

    public ColumnMetadata(String name, String typeName, int dataType, int columnSize, int decimalDigits,
                          boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue) {
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
        if (columnSize > 0 && !typeName.equalsIgnoreCase("INTEGER") && !typeName.equalsIgnoreCase("INT")
                && !typeName.equalsIgnoreCase("BIGINT") && !typeName.equalsIgnoreCase("BOOLEAN")
                && !typeName.equalsIgnoreCase("DATE") && !typeName.equalsIgnoreCase("TIME")
                && !typeName.equalsIgnoreCase("TIMESTAMP") && !typeName.equalsIgnoreCase("DATETIME")) {
            if (decimalDigits > 0) {
                return typeName + "(" + columnSize + "," + decimalDigits + ")";
            }
            return typeName + "(" + columnSize + ")";
        }
        return typeName;
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
