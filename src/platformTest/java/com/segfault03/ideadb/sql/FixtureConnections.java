package com.segfault03.ideadb.sql;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.ServiceContainerUtil;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import java.util.HashMap;
import java.util.Map;

/** Replaces the saved connections with an in-memory set, so fixtures never touch the IDE's password store. */
public final class FixtureConnections {
    private FixtureConnections() {}

    /** @param publishChanges whether adding or removing a connection announces it on the application bus, as it does in the IDE */
    public static DatabaseSettingsState install(Disposable parent, boolean publishChanges) {
        Map<String, DatabaseSettingsState.Secret> secrets = new HashMap<>();
        DatabaseSettingsState.CredentialStore store = new DatabaseSettingsState.CredentialStore() {
            @Override public DatabaseSettingsState.Secret get(String id) { return secrets.get(id); }
            @Override public void set(String id, DatabaseSettingsState.Secret secret) { secrets.put(id, secret); }
        };
        DatabaseSettingsState state = publishChanges ? new DatabaseSettingsState(store) : new DatabaseSettingsState(store, () -> {});
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), DatabaseSettingsState.class, state, parent);
        return state;
    }
}
