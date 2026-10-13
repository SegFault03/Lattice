package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class HsqlProvider implements DatabaseProvider {
    private final DriverDescriptor driver = new HsqlDriverDescriptor();
    private final SqlDialect dialect = new HsqlDialect();
    private final MetadataAdapter metadata = new HsqlMetadataAdapter();
    public Set<DatabaseFeature> features() { return Collections.unmodifiableSet(EnumSet.allOf(DatabaseFeature.class)); }
    public ConnectionForm connectionForm() { return ConnectionForm.HSQL_MODES; }
    public DatabaseType type() { return DatabaseType.HSQLDB; }
    public DriverDescriptor driver() { return driver; }
    public SqlDialect dialect() { return dialect; }
    public MetadataAdapter metadata() { return metadata; }
    public void configureDefaults(ConnectionConfig config) { config.setHsqlMode(HsqlMode.SERVER); }
    public int defaultPort() { return 9001; }
    public String defaultUser() { return "SA"; }
    public String defaultDatabase() { return "testdb"; }
    public String namespaceLabel() { return "schema"; }
    public String databaseNameProblem(ConnectionConfig config) {
        return config.getDatabaseName().isBlank() ? config.getHsqlMode() == HsqlMode.FILE ? "Choose a database path" : "Enter a database name" : null;
    }
    public boolean usesServer(ConnectionConfig config) { return config.getHsqlMode() == HsqlMode.SERVER; }
    public boolean acceptsUrl(String url) { return url.toLowerCase(Locale.ROOT).startsWith("jdbc:hsqldb:"); }
    public String buildJdbcUrl(ConnectionConfig config) {
        String db = config.getDatabaseName() == null || config.getDatabaseName().isBlank() ? defaultDatabase() : config.getDatabaseName().trim();
        return switch (config.getHsqlMode() == null ? HsqlMode.SERVER : config.getHsqlMode()) {
            case MEM -> "jdbc:hsqldb:mem:" + db + ";shutdown=true";
            case FILE -> "jdbc:hsqldb:file:" + db.replace("\\", "/") + ";shutdown=true";
            case SERVER -> {
                String host = config.getHost() == null || config.getHost().isBlank() ? "localhost" : config.getHost().trim();
                yield "jdbc:hsqldb:hsql://" + host + ":" + (config.getPort() > 0 ? config.getPort() : defaultPort()) + "/" + db;
            }
        };
    }
    public DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public void selectNamespace(Connection conn, String selected) throws SQLException {
        // Older drivers implement setSchema using executeQuery, which HSQLDB rejects.
        try (Statement statement = conn.createStatement()) { statement.execute("SET SCHEMA " + dialect.quoteIdentifier(selected)); }
    }
    public int fetchSize(int requested) { return requested; }
    public String sqlLiteral(Object value, String type) {
        String common = SqlLiterals.common(value); return common != null ? common : "'" + value.toString().replace("'", "''") + "'";
    }
}
