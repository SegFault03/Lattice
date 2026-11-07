package com.segfault03.ideadb.state;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSettingsStateTest {
    private static ConnectionConfig config(String name) {
        var config = new ConnectionConfig(DatabaseType.H2, name);
        config.setDatabaseName(name);
        return config;
    }

    @Test void editingAConnectionKeepsItsPositionAndStoresACopy() {
        var secrets = new HashMap<String, DatabaseSettingsState.Secret>();
        var vault = new DatabaseSettingsState.CredentialStore() {
            public DatabaseSettingsState.Secret get(String id) { return secrets.get(id); }
            public void set(String id, DatabaseSettingsState.Secret secret) { secrets.put(id, secret); }
        };
        var settings = new DatabaseSettingsState(vault, () -> { });
        var first = config("first"); var second = config("second"); var third = config("third");
        settings.addConnection(first); settings.addConnection(second); settings.addConnection(third);
        var edited = second.copy(); edited.setName("renamed");
        settings.updateConnection(edited);
        assertEquals(List.of("first", "renamed", "third"), settings.getConnections().stream().map(ConnectionConfig::getName).toList());
        edited.setName("mutated after save");
        assertEquals("renamed", settings.getConnection(second.getId()).getName());
        settings.updateConnection(config("unknown"));
        assertEquals("unknown", settings.getConnections().get(3).getName(), "A new connection is appended");
    }
}
