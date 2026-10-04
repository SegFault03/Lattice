package com.vibe.ideadb.model;

import java.util.*;

public final class RowIdentity {
    private RowIdentity() { }
    public static Map<String, Object> originalKeys(List<String> columns, List<Object> originalRow, List<String> primaryKeys) {
        if (primaryKeys.isEmpty()) throw new IllegalStateException("Table has no primary key");
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String key : primaryKeys) {
            int index = columns.indexOf(key);
            if (index < 0 || index >= originalRow.size() || originalRow.get(index) == null) {
                throw new IllegalStateException("Original primary key is missing: " + key);
            }
            keys.put(key, originalRow.get(index));
        }
        return Collections.unmodifiableMap(keys);
    }
}
