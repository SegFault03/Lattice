package com.vibe.ideadb.model;

import java.util.ArrayList;
import java.util.List;

public class TableMetadata {
    private final String catalog;
    private final String schema;
    private final String name;
    private final String type; // "TABLE", "VIEW", etc.
    private final List<ColumnMetadata> columns = new ArrayList<>();

    public TableMetadata(String catalog, String schema, String name, String type) {
        this.catalog = catalog;
        this.schema = schema;
        this.name = name;
        this.type = type != null ? type : "TABLE";
    }

    public String getCatalog() { return catalog; }
    public String getSchema() { return schema; }
    public String getName() { return name; }
    public String getType() { return type; }
    public List<ColumnMetadata> getColumns() { return columns; }

    public void addColumn(ColumnMetadata column) {
        columns.add(column);
    }

    public boolean isView() {
        return "VIEW".equalsIgnoreCase(type);
    }

    public List<String> getPrimaryKeyColumnNames() {
        List<String> pks = new ArrayList<>();
        for (ColumnMetadata col : columns) {
            if (col.isPrimaryKey()) {
                pks.add(col.getName());
            }
        }
        return pks;
    }

    public void setColumns(List<ColumnMetadata> newColumns) {
        columns.clear();
        if (newColumns != null) {
            columns.addAll(newColumns);
        }
    }

    public ColumnMetadata getColumn(String colName) {
        if (colName == null) return null;
        for (ColumnMetadata col:columns) if(col.getName().equals(colName)) return col;
        for (ColumnMetadata col : columns) {
            if (col.getName().equalsIgnoreCase(colName)) {
                return col;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
