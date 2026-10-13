package com.segfault03.ideadb.provider;

/** JDBC catalog and schema are independent; null means the connection's default. */
public record DatabaseNamespace(String catalog, String schema) {}
