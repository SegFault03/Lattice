package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

/** Built-ins are registered explicitly. A missing provider fails rather than inheriting another engine's SQL. */
public final class DatabaseProviders {
    private static final Map<DatabaseType, DatabaseProvider> PROVIDERS = Map.of(
            DatabaseType.MYSQL, new MySqlProvider(), DatabaseType.HSQLDB, new HsqlProvider());
    private DatabaseProviders() {}
    public static DatabaseProvider get(DatabaseType type) {
        DatabaseProvider provider = PROVIDERS.get(Objects.requireNonNull(type, "Database type"));
        if (provider == null) throw new IllegalArgumentException("No database provider registered for " + type);
        return provider;
    }
    public static Optional<DatabaseProvider> forUrl(String url) {
        if (url == null) return Optional.empty();
        return PROVIDERS.values().stream().filter(provider -> provider.acceptsUrl(url.trim())).findFirst();
    }
    public static DatabaseProvider forConnection(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        return forUrl(url).orElseThrow(() -> new SQLException("No database provider for connection URL"));
    }
}
