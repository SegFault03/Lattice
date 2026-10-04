package com.vibe.ideadb.state;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.HsqlMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "com.vibe.ideadb.state.DatabaseSettingsState",
        storages = @Storage("LatticeSettings.xml")
)
public class DatabaseSettingsState implements PersistentStateComponent<DatabaseSettingsState.State> {

    public static class State {
        public List<ConnectionConfig> connections = new ArrayList<>();
        public boolean showWelcomeScreen = true;
    }

    private State state = new State();

    public static DatabaseSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(DatabaseSettingsState.class);
    }

    public DatabaseSettingsState() {
        // Add a friendly default in-memory HSQLDB demo connection if list is empty
        if (state.connections.isEmpty()) {
            ConnectionConfig sampleHsql = new ConnectionConfig(DatabaseType.HSQLDB, "Demo HSQLDB (In-Memory)");
            sampleHsql.setHsqlMode(HsqlMode.MEM);
            sampleHsql.setDatabaseName("demodb");
            sampleHsql.setUser("SA");
            sampleHsql.setPassword("");
            state.connections.add(sampleHsql);

            ConnectionConfig serverHsql = new ConnectionConfig(DatabaseType.HSQLDB, "Local HSQLDB Server (testdb)");
            serverHsql.setHsqlMode(HsqlMode.SERVER);
            serverHsql.setHost("localhost");
            serverHsql.setPort(9001);
            serverHsql.setDatabaseName("testdb");
            serverHsql.setUser("SA");
            serverHsql.setPassword("");
            state.connections.add(serverHsql);

            ConnectionConfig mysql = new ConnectionConfig(DatabaseType.MYSQL, "Local MySQL (shop_db)");
            mysql.setHost("localhost");
            mysql.setPort(3306);
            mysql.setDatabaseName("shop_db");
            mysql.setUser("root");
            mysql.setPassword("");
            state.connections.add(mysql);
        }
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public List<ConnectionConfig> getConnections() {
        return state.connections;
    }

    public boolean isShowWelcomeScreen() {
        return state.showWelcomeScreen;
    }

    public void setShowWelcomeScreen(boolean show) {
        state.showWelcomeScreen = show;
    }

    public void addConnection(ConnectionConfig config) {
        state.connections.add(config);
    }

    public void removeConnection(String id) {
        state.connections.removeIf(c -> c.getId().equals(id));
    }

    public void updateConnection(ConnectionConfig config) {
        for (int i = 0; i < state.connections.size(); i++) {
            if (state.connections.get(i).getId().equals(config.getId())) {
                state.connections.set(i, config);
                return;
            }
        }
        state.connections.add(config);
    }

    public ConnectionConfig getConnection(String id) {
        for (ConnectionConfig c : state.connections) {
            if (c.getId().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
