package com.segfault03.ideadb.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.Topic;

import javax.swing.SwingUtilities;

/** Application-wide because saved data sources can be open in more than one project. */
public interface DatabaseChangeListener {
    Topic<DatabaseChangeListener> TOPIC = Topic.create("Lattice database changed", DatabaseChangeListener.class);

    void databaseChanged(DatabaseChange change);

    /** Call after the write/commit, independently of the initiating view's lifetime. */
    static void publish(DatabaseChange change) {
        var application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) return;
        SwingUtilities.invokeLater(() -> {
            if (!application.isDisposed()) application.getMessageBus().syncPublisher(TOPIC).databaseChanged(change);
        });
    }
}
