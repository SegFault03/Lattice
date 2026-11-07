package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

public interface MetadataAdapter {
    List<String> getDatabases(Connection connection, ConnectionConfig config) throws Exception;
    List<TableMetadata> getTables(Connection connection, ConnectionConfig config, String selected) throws Exception;
    List<ColumnMetadata> getColumns(Connection connection, ConnectionConfig config, String selected, String table) throws Exception;
    String currentSchema(Connection connection) throws SQLException;
    /** The namespace unqualified names resolve to on this connection: the schema, or the database where the engine has no schemas. */
    default String defaultNamespace(Connection connection, ConnectionConfig config) throws Exception { return currentSchema(connection); }
}
