package com.segfault03.ideadb.model;

import javax.swing.table.DefaultTableModel;

/** Arbitrary query results have no reliable row identity or write path. */
public final class ReadOnlyResultModel extends DefaultTableModel implements PrimaryKeyTableModel {
    private java.util.Set<Integer> primaryKeyColumns = java.util.Set.of();

    public void setResult(QueryResult result) {
        primaryKeyColumns = result.getPrimaryKeyColumns();
        super.setDataVector(result.getRows().stream().map(java.util.List::toArray).toArray(Object[][]::new),
                result.getColumnNames().toArray());
    }

    @Override public void setDataVector(Object[][] rows, Object[] columns) {
        primaryKeyColumns = java.util.Set.of();
        super.setDataVector(rows, columns);
    }

    @Override public boolean isPrimaryKeyColumn(int column) { return primaryKeyColumns.contains(column); }

    @Override public boolean isCellEditable(int row,int column) { return false; }
}
