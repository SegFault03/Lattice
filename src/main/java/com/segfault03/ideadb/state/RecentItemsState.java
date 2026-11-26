package com.segfault03.ideadb.state;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tables, views, consoles and SQL scripts recently opened in this project, newest first. Local workspace state, never shared. */
@State(name = "LatticeRecentItems", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class RecentItemsState implements PersistentStateComponent<RecentItemsState.State> {
    public static final int LIMIT = 50;
    public static final Topic<Listener> TOPIC = Topic.create("Lattice recent items changed", Listener.class);

    public interface Listener { void recentItemsChanged(); }

    public enum Kind { TABLE, CONSOLE, SCRIPT }

    public static final class Item {
        public Kind kind = Kind.TABLE;
        public String connectionId;
        public String database;
        public String catalog;
        public String schema;
        /** Table or view name; null for a console. */
        public String name;
        /** The table type the database reported, for example TABLE or VIEW. */
        public String tableType;
        /** Absolute path of a script file; null otherwise. */
        public String path;

        public Item() {}

        public static Item table(String connectionId, String database, String catalog, String schema, String name, String tableType) {
            Item item = new Item();
            item.connectionId = connectionId; item.database = database; item.catalog = catalog;
            item.schema = schema; item.name = name; item.tableType = tableType;
            return item;
        }

        public static Item console(String connectionId, String database) {
            Item item = new Item();
            item.kind = Kind.CONSOLE; item.connectionId = connectionId; item.database = database;
            return item;
        }

        public static Item script(String connectionId, String database, String path) {
            Item item = new Item();
            item.kind = Kind.SCRIPT; item.connectionId = connectionId; item.database = database; item.path = path;
            return item;
        }

        /** Two entries are the same place regardless of the table type reported when each was opened. */
        boolean samePlace(Item other) {
            return kind == other.kind && Objects.equals(connectionId, other.connectionId) && Objects.equals(database, other.database)
                    && Objects.equals(catalog, other.catalog) && Objects.equals(schema, other.schema) && Objects.equals(name, other.name) && Objects.equals(path, other.path);
        }
    }

    public static final class State { public List<Item> items = new ArrayList<>(); }

    private final Project project;
    private State state = new State();

    public RecentItemsState(Project project) { this.project = project; }

    public static RecentItemsState getInstance(Project project) { return project.getService(RecentItemsState.class); }

    @Override public synchronized State getState() { return state; }
    @Override public synchronized void loadState(State loaded) { state = loaded; }

    public synchronized List<Item> items() { return List.copyOf(state.items); }

    public void record(Item item) {
        synchronized (this) {
            state.items.removeIf(item::samePlace);
            state.items.add(0, item);
            while (state.items.size() > LIMIT) state.items.remove(state.items.size() - 1);
        }
        changed();
    }

    public void remove(Item item) {
        synchronized (this) { state.items.removeIf(item::samePlace); }
        changed();
    }

    public void clear() {
        synchronized (this) { state.items.clear(); }
        changed();
    }

    private void changed() {
        if (project != null && !project.isDisposed()) project.getMessageBus().syncPublisher(TOPIC).recentItemsChanged();
    }
}
