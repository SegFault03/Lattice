package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.time.*;
import java.util.*;

final class PostgresProvider implements DatabaseProvider {
    private final DriverDescriptor driver = new PostgresDriverDescriptor();
    private final SqlDialect dialect = new PostgresDialect();
    private final MetadataAdapter metadata = new PostgresMetadataAdapter();
    public DatabaseType type() { return DatabaseType.POSTGRESQL; }
    public Set<DatabaseFeature> features() { return Collections.unmodifiableSet(EnumSet.allOf(DatabaseFeature.class)); }
    public DriverDescriptor driver() { return driver; }
    public SqlDialect dialect() { return dialect; }
    public MetadataAdapter metadata() { return metadata; }
    public int defaultPort() { return 5432; }
    public String defaultUser() { return "postgres"; }
    public String defaultDatabase() { return "postgres"; }
    public String namespaceLabel() { return "schema"; }
    public boolean usesServer(ConnectionConfig config) { return true; }
    public String databaseNameProblem(ConnectionConfig config) { return config.getDatabaseName().isBlank() ? "Enter a database name" : null; }
    public boolean acceptsUrl(String url) { return url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:"); }
    public String buildJdbcUrl(ConnectionConfig config) {
        String host = config.getHost() == null || config.getHost().isBlank() ? "localhost" : config.getHost().trim();
        String database = config.getDatabaseName() == null || config.getDatabaseName().isBlank() ? defaultDatabase() : config.getDatabaseName().trim();
        String encoded = java.net.URLEncoder.encode(database, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "jdbc:postgresql://" + host + ":" + (config.getPort() > 0 ? config.getPort() : defaultPort()) + "/" + encoded + "?connectTimeout=10";
    }
    public DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public void selectNamespace(Connection conn, String selected) throws SQLException {
        try (var statement = conn.createStatement()) { statement.execute("SET search_path TO " + dialect.quoteIdentifier(selected) + ", pg_catalog"); }
    }
    public void initialize(Connection conn, ConnectionConfig config) throws SQLException {
        if (config.getCustomUrl().isBlank()) try (var statement = conn.createStatement()) { statement.execute("SET TIME ZONE 'UTC'"); }
    }
    public int fetchSize(int requested) { return requested; }
    public Object bitValue(String text) { return text; }
    public void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        // Unspecified strings let the server infer JSON, enum, UUID, array and temporal target types.
        if (value instanceof String || value instanceof OffsetTime) statement.setObject(index, value.toString(), Types.OTHER);
        else statement.setObject(index, value instanceof java.math.BigInteger number ? new java.math.BigDecimal(number) : value);
    }
    public Optional<Object> convertSpecialValue(ColumnMetadata column, Object value) {
        String type = column.getTypeName().toLowerCase(Locale.ROOT), text = value.toString().trim();
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("PostgreSQL values cannot contain NUL characters");
        if (type.equals("bit") || type.equals("varbit")) {
            if (!text.matches("[01]+") || column.getColumnSize()>0 && text.length()>column.getColumnSize()) throw new IllegalArgumentException("Expected a bit string within column capacity");
            return Optional.of(text);
        }
        if (text.equals("infinity") || text.equals("-infinity")) {
            if (column.getDataType() == Types.DATE || type.contains("timestamp")) return Optional.of(text);
        }
        if (type.equals("uuid")) return Optional.of(UUID.fromString(text));
        if (type.equals("timestamptz") || type.contains("timestamp with time zone")) {
            if (value instanceof OffsetDateTime) return Optional.of(value);
            String normalized = text.replace(' ', 'T').replaceFirst("([+-][0-9]{2})$", "$1:00");
            return Optional.of(OffsetDateTime.parse(normalized));
        }
        if (type.equals("timetz") || type.contains("time with time zone")) return Optional.of(OffsetTime.parse(text.replaceFirst("([+-][0-9]{2})$", "$1:00")));
        if (column.getDataType() == Types.TIME && text.matches("24:00:00(?:\\.0{1,6})?")) return Optional.of(text);
        return Optional.empty();
    }
    public Object readValue(ResultSet result, ResultSetMetaData meta, int index) throws SQLException {
        int type = meta.getColumnType(index);
        if (meta.getColumnTypeName(index).equals("bit") || meta.getColumnTypeName(index).equals("varbit") || type == Types.DATE || type == Types.TIME || type == Types.TIME_WITH_TIMEZONE || type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.ARRAY || type == Types.OTHER)
            return result.getString(index); // Detach vendor objects, arrays, infinity and timezone values faithfully.
        return result.getObject(index);
    }
    public String insertOverride(Connection conn) throws SQLException { return conn.getMetaData().getDatabaseMajorVersion() >= 10 ? " OVERRIDING SYSTEM VALUE" : ""; }
    public AutoCloseable readScope(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) return () -> {};
        connection.setAutoCommit(false); // pgJDBC cursors require an explicit transaction.
        return () -> {
            try { connection.rollback(); }
            catch (SQLException failure) { try { connection.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
            connection.setAutoCommit(true);
        };
    }
    public void requireTransactionalTable(Connection conn, ConnectionConfig config, String schema, String table) throws SQLException {
        try (var statement = conn.prepareStatement("SELECT c.relkind FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=COALESCE(?,current_schema()) AND c.relname=?")) {
            statement.setString(1, schema); statement.setString(2, table);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !Set.of("r", "p").contains(result.getString(1))) throw new SQLException("Atomic grid changes require a native PostgreSQL table");
            }
        }
    }
    public List<String> dataTypes() { return List.of("INTEGER", "BIGINT", "SMALLINT", "VARCHAR", "TEXT", "BOOLEAN", "NUMERIC", "DOUBLE PRECISION", "REAL", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "BYTEA", "UUID", "JSON", "JSONB"); }
    public String sqlLiteral(Object value, String type) {
        if (value == null) return "NULL";
        if (value instanceof byte[] bytes) return "decode('" + HexFormat.of().formatHex(bytes) + "','hex')";
        if (value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        if (value instanceof Number) return SqlLiterals.common(value);
        if (type.equalsIgnoreCase("bit") || type.equalsIgnoreCase("varbit")) return "B'" + value.toString().replace("'","''") + "'";
        return textLiteral(value.toString());
    }
    static String textLiteral(String value) {
        return "E'" + value.replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }
}
