package com.segfault03.ideadb.sql.schema;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/** Application-wide: a connection's schema view changed (loaded, refreshed, or failed to load). */
public interface SqlSchemaListener {
    Topic<SqlSchemaListener> TOPIC = Topic.create("Lattice SQL schema changed", SqlSchemaListener.class);

    void schemaChanged(@NotNull String connectionId);

    static void publish(@NotNull String connectionId) {
        var application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) return;
        application.invokeLater(() -> {
            if (!application.isDisposed()) application.getMessageBus().syncPublisher(TOPIC).schemaChanged(connectionId);
        });
    }
}
