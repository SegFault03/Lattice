package com.segfault03.ideadb.state;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.segfault03.ideadb.model.ConnectionConfig;
import org.jetbrains.annotations.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.*;

@State(name = "com.segfault03.ideadb.state.DatabaseSettingsState", storages = @Storage("LatticeSettings.xml"))
public class DatabaseSettingsState implements PersistentStateComponent<DatabaseSettingsState.State> {
    public static class State {
        public List<ConnectionConfig> connections = new ArrayList<>();
        public boolean showWelcomeScreen = true;
    }
    public record Secret(String password, String customUrl) {}
    public interface CredentialStore {
        Secret get(String id);
        void set(String id, Secret secret);
    }
    private static final class SafeCredentials implements CredentialStore {
        private CredentialAttributes attributes(String id) { return new CredentialAttributes("Lattice connection " + id); }
        public Secret get(String id) {
            Credentials credentials = PasswordSafe.getInstance().get(attributes(id));
            String value = credentials == null ? null : credentials.getPasswordAsString();
            if (value == null) return null;
            String[] parts = value.split("\n", -1);
            return new Secret(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        public void set(String id, Secret secret) {
            PasswordSafe.getInstance().set(attributes(id), secret == null ? null : new Credentials("Lattice", encode(secret.password()) + "\n" + encode(secret.customUrl())));
        }
        private String encode(String value) { return Base64.getEncoder().encodeToString(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8)); }
        private String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    }
    private State state = new State();
    private final CredentialStore credentials;
    public DatabaseSettingsState() { this(new SafeCredentials()); }
    private final Runnable changed;
    public DatabaseSettingsState(CredentialStore credentials) { this(credentials, DatabaseSettingsState::publishChange); }
    public DatabaseSettingsState(CredentialStore credentials, Runnable changed) {
        this.credentials = Objects.requireNonNull(credentials);
        this.changed = Objects.requireNonNull(changed);
    }
    private static void publishChange() {
        var application = ApplicationManager.getApplication();
        if (application != null) application.getMessageBus().syncPublisher(DatabaseSettingsListener.TOPIC).connectionsChanged();
    }
    public static DatabaseSettingsState getInstance() { return ApplicationManager.getApplication().getService(DatabaseSettingsState.class); }

    /** Only sanitized copies enter the IDE's XML persistence pipeline. */
    @Override public synchronized State getState() {
        State persisted = new State(); persisted.showWelcomeScreen = state.showWelcomeScreen;
        for (ConnectionConfig config : state.connections) {
            ConnectionConfig copy = config.copy(); copy.setPassword(""); copy.setCustomUrl("");
            persisted.connections.add(copy);
        }
        return persisted;
    }
    @Override public synchronized void loadState(@NotNull State loaded) {
        State restored = new State(); restored.showWelcomeScreen = loaded.showWelcomeScreen;
        for (ConnectionConfig stored : loaded.connections) {
            ConnectionConfig copy = stored.copy();
            // Old XML may contain either a plain password or a JDBC URL containing credentials.
            if (!Objects.requireNonNullElse(copy.getPassword(), "").isEmpty() || !Objects.requireNonNullElse(copy.getCustomUrl(), "").isEmpty()) {
                credentials.set(copy.getId(), new Secret(copy.getPassword(), copy.getCustomUrl()));
            } else {
                Secret secret = credentials.get(copy.getId());
                if (secret != null) { copy.setPassword(secret.password()); copy.setCustomUrl(secret.customUrl()); }
            }
            restored.connections.add(copy);
        }
        var manager = com.segfault03.ideadb.service.DatabaseConnectionManager.getInstance();
        for (ConnectionConfig old : state.connections) {
            if (restored.connections.stream().noneMatch(c -> c.getId().equals(old.getId()))) manager.removeConfiguration(old.getId());
        }
        restored.connections.forEach(manager::registerConfiguration);
        state = restored;
        changed.run();
    }
    public synchronized List<ConnectionConfig> getConnections() { return state.connections.stream().map(ConnectionConfig::copy).toList(); }
    public synchronized boolean isShowWelcomeScreen() { return state.showWelcomeScreen; }
    public synchronized void setShowWelcomeScreen(boolean show) { state.showWelcomeScreen = show; }
    public void addConnection(ConnectionConfig config) { updateConnection(config); }
    public synchronized void removeConnection(String id) {
        credentials.set(id, null);
        com.segfault03.ideadb.service.DatabaseConnectionManager.getInstance().removeConfiguration(id);
        state.connections.removeIf(c -> c.getId().equals(id));
        changed.run();
    }
    public synchronized void updateConnection(ConnectionConfig config) {
        credentials.set(config.getId(), new Secret(config.getPassword(), config.getCustomUrl()));
        state.connections.removeIf(c -> c.getId().equals(config.getId()));
        state.connections.add(config.copy());
        com.segfault03.ideadb.service.DatabaseConnectionManager.getInstance().registerConfiguration(config);
        changed.run();
    }
    public synchronized ConnectionConfig getConnection(String id) {
        return state.connections.stream().filter(c -> c.getId().equals(id)).findFirst().map(ConnectionConfig::copy).orElse(null);
    }
}
