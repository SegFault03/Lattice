package com.vibe.ideadb.model;

public class ColumnDefinition {
    private String name = "";
    private String type = "VARCHAR";
    private int size = 255;
    private boolean nullable = true;
    private boolean primaryKey = false;
    private boolean autoIncrement = false;
    private String defaultValue = "";

    public ColumnDefinition() {
    }

    public ColumnDefinition(String name, String type, int size, boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue) {
        this.name = name;
        this.type = type;
        this.size = size;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.autoIncrement = autoIncrement;
        this.defaultValue = defaultValue;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }

    public boolean isPrimaryKey() { return primaryKey; }
    public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }

    public boolean isAutoIncrement() { return autoIncrement; }
    public void setAutoIncrement(boolean autoIncrement) { this.autoIncrement = autoIncrement; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
}
