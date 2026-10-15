package com.segfault03.ideadb.model;

/** Key membership uses model column indices, so aliases and reordered columns remain unambiguous. */
public interface KeyTableModel {
    boolean isPrimaryKeyColumn(int column);
    boolean isForeignKeyColumn(int column);
}
