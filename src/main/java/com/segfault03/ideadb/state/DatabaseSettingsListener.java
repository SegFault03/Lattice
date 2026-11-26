package com.segfault03.ideadb.state;

import com.intellij.util.messages.Topic;

/** Application-wide settings changes, including additions made from the explorer or the connection dialog. */
public interface DatabaseSettingsListener {
    Topic<DatabaseSettingsListener> TOPIC = Topic.create("Lattice connection settings changed", DatabaseSettingsListener.class);
    void connectionsChanged();
}
