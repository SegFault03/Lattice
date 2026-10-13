package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

/** Engine-specific behavior; shared services depend on this contract, never engine branches. */
public interface DatabaseProvider {
    enum ConnectionForm { SERVER, HSQL_MODES }
    default ConnectionForm connectionForm() { return ConnectionForm.SERVER; }
    DatabaseType type();
    default Set<DatabaseFeature> features() { return Set.of(); }
    default void requireFeature(DatabaseFeature feature) {
        if (!features().contains(feature)) throw new UnsupportedOperationException(type().getDisplayName() + " does not support " + feature);
    }

    DriverDescriptor driver();
    SqlDialect dialect();
    MetadataAdapter metadata();
    default void configureDefaults(ConnectionConfig config) {}
    int defaultPort();
    String defaultUser();
    String defaultDatabase();
    String namespaceLabel();
    default String namespaceNameLabel() {
        String label = namespaceLabel();
        return Character.toUpperCase(label.charAt(0)) + label.substring(1) + " name:";
    }
    boolean usesServer(ConnectionConfig config);
    default String databaseNameProblem(ConnectionConfig config) { return null; }
    String buildJdbcUrl(ConnectionConfig config);
    boolean acceptsUrl(String url);
    DatabaseNamespace namespace(String selected);
    void selectNamespace(Connection connection, String selected) throws SQLException;
    default void initialize(Connection connection, ConnectionConfig config) throws SQLException {}
    int fetchSize(int requested);
    default void requireTransactionalTable(Connection connection, ConnectionConfig config, String selected, String table) throws SQLException {
        if (!connection.getMetaData().supportsTransactions()) throw new SQLException("This database does not support transactional grid changes");
    }
    default Object bitValue(String text) { return new java.math.BigInteger(text, 2).toByteArray(); }
    default Object timeValue(ColumnMetadata column, Object value) { return value instanceof java.sql.Time time ? time.toLocalTime() : java.time.LocalTime.parse(value.toString().trim()); }
    default Object boundValue(Object value) { return value; }
    String sqlLiteral(Object value, String type);
}
