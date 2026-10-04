package com.segfault03.ideadb.model;

import javax.swing.table.DefaultTableModel;

/** Arbitrary query results have no reliable row identity or write path. */
public final class ReadOnlyResultModel extends DefaultTableModel {
    @Override public boolean isCellEditable(int row,int column) { return false; }
}
