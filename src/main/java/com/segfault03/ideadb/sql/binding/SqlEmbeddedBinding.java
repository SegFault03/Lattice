package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a SQL editor embedded in the plugin's own UI (the console, a table's WHERE and ORDER BY fields) knows without a
 * saved file binding: the connection it works on, the namespace unqualified names resolve to, and, for a field that
 * holds a fragment of a statement, the start of the statement that the fragment completes. It lives on the editor's
 * file, so it is never written to the workspace file and goes away with the editor.
 */
public final class SqlEmbeddedBinding {
    private static final Key<SqlEmbeddedBinding> KEY = Key.create("lattice.sql.embedded");

    private final String connectionId;
    private final String leadIn;
    private volatile String namespace;

    public SqlEmbeddedBinding(@NotNull String connectionId, @Nullable String namespace, @NotNull String leadIn) {
        this.connectionId = connectionId;
        this.namespace = namespace == null || namespace.isEmpty() ? null : namespace;
        this.leadIn = leadIn;
    }

    public void attachTo(@NotNull VirtualFile file) { file.putUserData(KEY, this); }

    public static @Nullable SqlEmbeddedBinding of(@Nullable VirtualFile file) { return file == null ? null : file.getUserData(KEY); }

    public @NotNull String connectionId() { return connectionId; }

    /** The namespace unqualified names resolve to, or null for the connection's own default. */
    public @Nullable String namespace() { return namespace; }
    public void namespace(@Nullable String namespace) { this.namespace = namespace == null || namespace.isEmpty() ? null : namespace; }

    /**
     * The text that stands before the editor's own in the statement it belongs to ({@code select * from t where }), empty
     * for an editor that holds whole statements. The analysis sees it; the user does not.
     */
    public @NotNull String leadIn() { return leadIn; }
}
