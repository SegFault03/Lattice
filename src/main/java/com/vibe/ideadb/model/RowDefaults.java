package com.vibe.ideadb.model;

/** SQL DEFAULT is an explicit pending-cell state, distinct from Java null and ordinary text. */
public final class RowDefaults {
    private RowDefaults() {}
    public enum Value { USE_DEFAULT; @Override public String toString() { return "(Default)"; } }
    public static Object initialValue(ColumnMetadata column) {
        if(column!=null && column.isAutoIncrement()) return "(Auto)";
        return column!=null && column.getDefaultValue()!=null ? Value.USE_DEFAULT : null;
    }
}
