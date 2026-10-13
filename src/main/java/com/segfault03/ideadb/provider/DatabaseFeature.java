package com.segfault03.ideadb.provider;

/** Optional operations must be explicitly advertised by each provider. */
public enum DatabaseFeature {
    CREATE_NAMESPACE, DROP_NAMESPACE, CREATE_TABLE, DROP_TABLE, TRUNCATE_TABLE,
    ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, MODIFY_COLUMN, RENAME_TABLE, NATIVE_CREATE_STATEMENT
}
