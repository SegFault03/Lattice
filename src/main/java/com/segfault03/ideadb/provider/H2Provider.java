package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class H2Provider implements DatabaseProvider {
    private final DriverDescriptor driver = new H2DriverDescriptor();
    private final SqlDialect dialect = new H2Dialect();
    private final MetadataAdapter metadata = new H2MetadataAdapter();
    public DatabaseType type() { return DatabaseType.H2; }
    public DriverDescriptor driver() { return driver; }
    public SqlDialect dialect() { return dialect; }
    public MetadataAdapter metadata() { return metadata; }
    public Set<DatabaseFeature> features() { return Collections.unmodifiableSet(EnumSet.allOf(DatabaseFeature.class)); }
    // Reuse the persisted server/file/memory selector; H2 URL semantics stay here.
    public ConnectionForm connectionForm() { return ConnectionForm.HSQL_MODES; }
    public void configureDefaults(ConnectionConfig config) { config.setHsqlMode(HsqlMode.MEM); }
    public int defaultPort() { return 9092; }
    public String defaultUser() { return "sa"; }
    public String defaultDatabase() { return "testdb"; }
    public String namespaceLabel() { return "schema"; }
    public boolean usesServer(ConnectionConfig config) { return config.getHsqlMode() == HsqlMode.SERVER; }
    public String databaseNameProblem(ConnectionConfig config) {
        return config.getDatabaseName().isBlank() ? config.getHsqlMode() == HsqlMode.FILE ? "Choose a database path" : "Enter a database name" : null;
    }
    public boolean acceptsUrl(String url) { return url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:"); }
    public String buildJdbcUrl(ConnectionConfig config) {
        String db = config.getDatabaseName() == null || config.getDatabaseName().isBlank() ? defaultDatabase() : config.getDatabaseName().trim();
        return switch (config.getHsqlMode() == null ? HsqlMode.MEM : config.getHsqlMode()) {
            case MEM -> "jdbc:h2:mem:" + db;
            case FILE -> {
                String path = db.endsWith(".mv.db") ? db.substring(0, db.length() - ".mv.db".length()) : db;
                path = path.replace('\\', '/');
                if (!path.startsWith("/") && !path.startsWith("~") && !path.startsWith("./") && !path.startsWith("../") && !path.matches("^[A-Za-z]:/.*")) path = "./" + path;
                yield "jdbc:h2:file:" + path;
            }
            case SERVER -> {
                String host = config.getHost() == null || config.getHost().isBlank() ? "localhost" : config.getHost().trim();
                yield "jdbc:h2:tcp://" + host + ":" + (config.getPort() > 0 ? config.getPort() : defaultPort()) + "/" + db;
            }
        };
    }
    public DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public void selectNamespace(Connection conn, String selected) throws SQLException { conn.setSchema(selected); }
    public int fetchSize(int requested) { return requested; }
    public List<String> dataTypes() { return List.of("INT", "BIGINT", "SMALLINT", "TINYINT", "VARCHAR", "CHAR", "CLOB", "BOOLEAN", "DECIMAL", "REAL", "DOUBLE PRECISION", "DATE", "TIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "BINARY VARYING", "BLOB", "JSON", "UUID"); }
    public String sqlLiteral(Object value, String type) {
        String common = SqlLiterals.common(value);
        return common != null ? common : "'" + value.toString().replace("'", "''") + "'";
    }
}
