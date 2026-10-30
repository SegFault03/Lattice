package com.segfault03.ideadb.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which {@code TABLE_TYPE} values of a driver are objects a user queries. System, temporary, index and sequence
 * types are never included, whatever the driver reports.
 */
final class TableTypes {
    private static final Set<String> USER_OBJECTS = Set.of("TABLE", "BASE TABLE", "VIEW", "PARTITIONED TABLE",
            "MATERIALIZED VIEW", "FOREIGN TABLE", "SYSTEM VERSIONED TABLE");
    private static final String[] FALLBACK = {"TABLE", "VIEW"};

    private TableTypes() {}

    /** The reported types that are user objects, in the driver's spelling; {@code TABLE} and {@code VIEW} when it reports none. */
    static String[] select(List<String> reported) {
        List<String> chosen = new ArrayList<>();
        for (String type : reported) {
            if (type != null && USER_OBJECTS.contains(type.strip().toUpperCase(Locale.ROOT)) && !chosen.contains(type)) chosen.add(type);
        }
        return chosen.isEmpty() ? FALLBACK.clone() : chosen.toArray(String[]::new);
    }
}
