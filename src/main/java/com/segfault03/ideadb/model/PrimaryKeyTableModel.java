package com.segfault03.ideadb.model;

/** Key membership uses model column indices, so aliases and reordered columns remain unambiguous. */
public interface PrimaryKeyTableModel {
    boolean isPrimaryKeyColumn(int column);
}
