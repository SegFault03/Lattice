package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import org.h2.tools.Server;
import java.sql.*;
import java.util.UUID;

/** An owned loopback server with a keeper connection; never enables remote database creation. */
public final class H2ServerFixture implements AutoCloseable {
    private final Connection keeper;
    private final Server server;
    private final String name = "fixture_" + UUID.randomUUID().toString().replace("-", "");
    public H2ServerFixture(boolean ssl) throws SQLException {
        keeper = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "fixture-password");
        try {
            server = ssl ? Server.createTcpServer("-tcpPort", "0", "-tcpSSL", "-tcpDaemon").start()
                    : Server.createTcpServer("-tcpPort", "0", "-tcpDaemon").start();
        } catch (SQLException failure) { keeper.close(); throw failure; }
    }
    public ConnectionConfig config() {
        var config = new ConnectionConfig(DatabaseType.H2, "owned server");
        config.setHsqlMode(HsqlMode.SERVER); config.setHost("127.0.0.1"); config.setPort(server.getPort());
        config.setDatabaseName("mem:" + name); config.setPassword("fixture-password");
        config.setCustomUrl("jdbc:h2:" + server.getURL().replace("localhost", "127.0.0.1") + "/mem:" + name);
        return config;
    }
    public void close() throws SQLException { try { server.stop(); } finally { keeper.close(); } }
}
