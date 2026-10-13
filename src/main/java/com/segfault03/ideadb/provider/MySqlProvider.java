package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class MySqlProvider implements DatabaseProvider {
    private final DriverDescriptor driver = new MySqlDriverDescriptor();
    private final SqlDialect dialect = new MySqlDialect();
    private final MetadataAdapter metadata = new MySqlMetadataAdapter();
    public Set<DatabaseFeature> features() { return Collections.unmodifiableSet(EnumSet.allOf(DatabaseFeature.class)); }
    public DatabaseType type() { return DatabaseType.MYSQL; }
    public DriverDescriptor driver() { return driver; }
    public SqlDialect dialect() { return dialect; }
    public MetadataAdapter metadata() { return metadata; }
    public int defaultPort() { return 3306; }
    public String defaultUser() { return "root"; }
    public String defaultDatabase() { return ""; }
    public String namespaceLabel() { return "database"; }
    public boolean usesServer(ConnectionConfig config) { return true; }
    public boolean acceptsUrl(String url) { return url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:"); }
    public String buildJdbcUrl(ConnectionConfig config) {
        String db = config.getDatabaseName() == null ? "" : config.getDatabaseName().trim();
        String host = config.getHost() == null || config.getHost().isBlank() ? "localhost" : config.getHost().trim();
        int port = config.getPort() > 0 ? config.getPort() : defaultPort();
        return "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useLegacyDatetimeCode=false&forceConnectionTimeZoneToSession=true&characterEncoding=utf8";
    }
    public DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(selected, null); }
    public void selectNamespace(Connection conn, String selected) throws SQLException { if (!selected.equals(conn.getCatalog())) conn.setCatalog(selected); }
    public void initialize(Connection conn, ConnectionConfig config) throws SQLException {
        if (config.getCustomUrl().isBlank()) try (Statement statement = conn.createStatement()) { statement.execute("SET SESSION time_zone='+00:00'"); }
    }
    public int fetchSize(int requested) { return Integer.MIN_VALUE; }
    public void requireTransactionalTable(Connection conn, ConnectionConfig config, String selected, String table) throws SQLException {
        try (var statement = conn.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE table_schema=COALESCE(?,DATABASE()) AND table_name=?")) {
            statement.setString(1, selected); statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1))) throw new SQLException("Atomic grid changes require an InnoDB table");
            }
        }
    }
    public Object bitValue(String text) { return new java.math.BigInteger(text, 2); }
    public Object boundValue(Object value) { return value instanceof java.math.BigInteger integer ? new java.math.BigDecimal(integer) : value; }
    public Object timeValue(ColumnMetadata column, Object value) {
        String text = value.toString().trim();
        var match = java.util.regex.Pattern.compile("-?(\\d{1,3}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,6}))?").matcher(text);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 838) throw new IllegalArgumentException("MySQL TIME must be within -838:59:59 and 838:59:59");
        if (match.group(4) != null && match.group(4).length() > column.getDecimalDigits()) throw new IllegalArgumentException("TIME exceeds fractional precision");
        return text;
    }
    public String sqlLiteral(Object value, String type) {
        String common = SqlLiterals.common(value); if (common != null) return common;
        if (value instanceof java.sql.Timestamp timestamp && type.toUpperCase(Locale.ROOT).contains("TIMESTAMP")) {
            var instant = timestamp.toInstant();
            var seconds = java.math.BigDecimal.valueOf(instant.getEpochSecond()).add(java.math.BigDecimal.valueOf(instant.getNano(), 9));
            return "FROM_UNIXTIME(" + seconds.toPlainString() + ")";
        }
        return "CONVERT(X'" + java.util.HexFormat.of().formatHex(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "' USING utf8mb4)";
    }
}
