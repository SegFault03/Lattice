package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionBlockingTest {
    @Test void isConnectedDoesNotWaitForAConnectInProgress() throws Exception {
        // The endpoint accepts the TCP connection but never answers the JDBC handshake, so the connect blocks.
        try (ServerSocket silent = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            DatabaseConnectionManager manager = new DatabaseConnectionManager();
            ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Blocked");
            config.setCustomUrl("jdbc:hsqldb:hsql://127.0.0.1:" + silent.getLocalPort() + "/blocked");
            manager.registerConfiguration(config);
            CompletableFuture<Object> connecting = CompletableFuture.supplyAsync(() -> {
                try { return manager.getConnection(config); } catch (Exception e) { return e; }
            });
            Socket accepted = silent.accept();
            try {
                Thread.sleep(200);
                assertFalse(connecting.isDone(), "connect should still be blocked");
                CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(() -> manager.isConnected(config.getId()));
                assertFalse(probe.get(2, TimeUnit.SECONDS));
            } finally {
                accepted.close();
            }
            assertInstanceOf(Exception.class, connecting.get(30, TimeUnit.SECONDS));
            manager.dispose();
        }
    }
}
