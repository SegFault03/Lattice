package com.segfault03.ideadb.model;

/** The column a foreign key points to. The namespace is the database (MySQL) or schema (the other engines). */
public record ColumnReference(String namespace, String table, String column) {}
