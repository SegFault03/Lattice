package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration") @Tag("h2integration")
class H2FunctionalIntegrationTest {
    @TempDir Path directory;
    @Test void tcpProductionWorkflowAndRegression() throws Exception {
        try (var fixture = new H2ServerFixture(false)) {
            var config = fixture.config(); config.setCustomUrl(""); // Exercise the standard form URL.
            ProviderEndToEndTest.workflow(config);
            H2RegressionTest.regressionFlow(config);
        }
    }
    @Test void sslProductionWorkflowAndRegression() throws Exception {
        // H2's TLS fixture creates its own ephemeral certificate; no host trust store is changed.
        // H2 creates .h2.keystore under user.home unless javax.net.ssl.keyStore is explicit.
        Path keyStore = directory.resolve("fixture.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "h2", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-validity", "1", "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", "fixture-password", "-keypass", "fixture-password").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        var old = new HashMap<String,String>();
        for (var entry : Map.of("javax.net.ssl.keyStore", keyStore.toString(), "javax.net.ssl.keyStorePassword", "fixture-password", "javax.net.ssl.trustStore", keyStore.toString(), "javax.net.ssl.trustStorePassword", "fixture-password").entrySet()) old.put(entry.getKey(), System.setProperty(entry.getKey(), entry.getValue()));
        try (var fixture = new H2ServerFixture(true)) {
            assertTrue(fixture.config().buildJdbcUrl().startsWith("jdbc:h2:ssl:"));
            ProviderEndToEndTest.workflow(fixture.config());
            H2RegressionTest.regressionFlow(fixture.config());
        } finally {
            old.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }
    @Test void fileProductionWorkflow() throws Exception {
        var config = new ConnectionConfig(DatabaseType.H2, "file functional");
        config.setHsqlMode(HsqlMode.FILE); config.setDatabaseName(directory.resolve("functional").toString());
        ProviderEndToEndTest.workflow(config);
        assertTrue(Files.isRegularFile(directory.resolve("functional.mv.db")));
    }
    @Test void authenticationFailureIsRedactedAndServerRecovers() throws Exception {
        try (var fixture = new H2ServerFixture(false)) {
            var manager = new DatabaseConnectionManager();
            try {
                var config = fixture.config(); assertTrue(manager.testConnection(config).isSuccess());
                var wrong = config.copy(); wrong.setPassword("private-wrong-password");
                var result = manager.testConnection(wrong);
                assertFalse(result.isSuccess()); assertFalse(result.getSummaryMessage().contains("private-wrong-password"));
                var missing = config.copy(); missing.setUser("no_such_user");
                assertFalse(manager.testConnection(missing).isSuccess());
                assertTrue(manager.testConnection(config).isSuccess());
            } finally { manager.dispose(); }
        }
    }
}
