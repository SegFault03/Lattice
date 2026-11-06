package com.segfault03.ideadb.sql.status;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Lattice's state for one file, as its editor's status icon shows it. Work in progress comes first (the schema being
 * read, then the SQL being checked or found), then what limits the checks (no connection, a schema that could not be
 * read, may be out of date or was never read), then ready.
 */
public record LatticeFileStatus(@NotNull State state, @Nullable ConnectionConfig connection, int tables, int statements) {
    public enum State {
        LOADING_SCHEMA(true), CHECKING(true), READING(true),
        NO_CONNECTION(false), SCHEMA_FAILED(false), SCHEMA_STALE(false), SCHEMA_MISSING(false), READY(false);
        public final boolean busy;
        State(boolean busy) { this.busy = busy; }
    }

    /** What the service and the activity say about the file; {@code statements} is the number of cards its editor shows. */
    public static @NotNull LatticeFileStatus of(@NotNull Project project, @NotNull VirtualFile file, @Nullable ConnectionConfig connection, int statements) {
        LatticeActivity.Stage stage = LatticeActivity.current(file);
        if (connection == null) {
            State state = stage == LatticeActivity.Stage.CHECKING ? State.CHECKING : stage == LatticeActivity.Stage.READING ? State.READING : State.NO_CONNECTION;
            return new LatticeFileStatus(state, null, 0, statements);
        }
        SqlSchemaService service = SqlSchemaService.getInstance();
        String id = connection.getId();
        SqlSchema schema = service.schema(connection);
        int tables = schema == null ? 0 : schema.tables(null).size();
        State state;
        if (service.isLoading(id)) state = State.LOADING_SCHEMA;
        else if (stage == LatticeActivity.Stage.CHECKING) state = State.CHECKING;
        else if (stage == LatticeActivity.Stage.READING) state = State.READING;
        else if (service.hasFailed(id)) state = State.SCHEMA_FAILED;
        else if (service.isStale(id)) state = State.SCHEMA_STALE;
        else if (schema == null) state = State.SCHEMA_MISSING;
        else state = State.READY;
        return new LatticeFileStatus(state, connection, tables, statements);
    }

    public @NotNull Icon icon() {
        return switch (state) {
            case LOADING_SCHEMA, CHECKING, READING -> AnimatedIcon.Default.INSTANCE;
            case NO_CONNECTION -> Icons.PLUG_DISCONNECTED;
            case SCHEMA_FAILED -> AllIcons.General.Warning;
            case SCHEMA_STALE, SCHEMA_MISSING, READY -> Icons.LATTICE;
        };
    }

    /** The one line the icon's tooltip shows. */
    public @NotNull String summary() {
        String name = connection == null ? "" : connection.getName();
        return switch (state) {
            case LOADING_SCHEMA -> "Lattice: reading the schema of " + name + "…";
            case CHECKING -> "Lattice: checking SQL…";
            case READING -> "Lattice: finding SQL…";
            case NO_CONNECTION -> "Lattice: no connection; SQL is checked for syntax only";
            case SCHEMA_FAILED -> "Lattice: could not read the schema of " + name + "; table and column names are not checked";
            case SCHEMA_STALE -> "Lattice: the schema of " + name + " may be out of date; names are checked once it is read again";
            case SCHEMA_MISSING -> "Lattice: the schema of " + name + " has not been read yet";
            case READY -> "Lattice: SQL checked against " + name;
        };
    }

    /** What the schema line of the details says. */
    public @NotNull String schemaText() {
        return switch (state) {
            case LOADING_SCHEMA -> "Reading…";
            case SCHEMA_FAILED -> "Could not be read; retried shortly";
            case SCHEMA_STALE -> "May be out of date";
            case SCHEMA_MISSING -> "Not read yet";
            case NO_CONNECTION -> "None";
            case CHECKING, READING, READY -> tables == 1 ? "1 table" : tables + " tables";
        };
    }
}
