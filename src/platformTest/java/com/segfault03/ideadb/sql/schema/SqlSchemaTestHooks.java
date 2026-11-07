package com.segfault03.ideadb.sql.schema;

import java.util.function.Consumer;

/** Lets tests in other packages hold back or fail the loader's column reads. */
public final class SqlSchemaTestHooks {
    private SqlSchemaTestHooks() {}

    public static void onColumnRead(Consumer<String> hook) { SqlSchemaLoader.columnReadHook = hook; }

    public static void reset() { SqlSchemaLoader.columnReadHook = table -> {}; }
}
