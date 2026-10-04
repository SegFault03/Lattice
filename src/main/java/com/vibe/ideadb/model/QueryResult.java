package com.vibe.ideadb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueryResult {
    private final boolean resultSet;
    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final List<List<Object>> rows;
    private final int affectedRows;
    private final long executionTimeMs;
    private final String message;
    private final String error;
    private boolean truncated;

    private QueryResult(boolean resultSet, List<String> columnNames, List<String> columnTypes,
                        List<List<Object>> rows, int affectedRows, long executionTimeMs,
                        String message, String error) {
        this.resultSet = resultSet;
        this.columnNames = columnNames != null ? columnNames : Collections.emptyList();
        this.columnTypes = columnTypes != null ? columnTypes : Collections.emptyList();
        this.rows = rows != null ? rows : Collections.emptyList();
        this.affectedRows = affectedRows;
        this.executionTimeMs = executionTimeMs;
        this.message = message;
        this.error = error;
    }

    public static QueryResult forResultSet(List<String> columns, List<String> columnTypes, List<List<Object>> rows, long timeMs) {
        String msg = String.format("Query executed in %d ms. Retrieved %d row(s).", timeMs, rows.size());
        return new QueryResult(true, columns, columnTypes, rows, 0, timeMs, msg, null);
    }

    public static QueryResult forResultSet(List<String> columns, List<String> types, List<List<Object>> rows, long timeMs, boolean truncated) {
        QueryResult result = forResultSet(columns, types, rows, timeMs); result.truncated = truncated; return result;
    }
    public boolean isTruncated() { return truncated; }
    public static QueryResult forUpdate(int affectedRows, long timeMs) {
        String msg = String.format("Query executed in %d ms. Affected rows: %d.", timeMs, affectedRows);
        return new QueryResult(false, null, null, null, affectedRows, timeMs, msg, null);
    }

    public static QueryResult forError(String error, long timeMs) {
        return new QueryResult(false, null, null, null, 0, timeMs, null, error);
    }

    public boolean isResultSet() { return resultSet; }
    public boolean hasError() { return error != null && !error.isEmpty(); }
    public List<String> getColumnNames() { return columnNames; }
    public List<String> getColumnTypes() { return columnTypes; }
    public List<List<Object>> getRows() { return rows; }
    public int getAffectedRows() { return affectedRows; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public String getMessage() { return truncated ? message + " Result limit reached; additional rows were omitted." : message; }
    public String getError() { return error; }
}
