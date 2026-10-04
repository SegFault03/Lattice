package com.vibe.ideadb.editor;

import com.intellij.util.messages.Topic;
import com.vibe.ideadb.model.*;

public interface TableRenameListener {
    Topic<TableRenameListener> TOPIC=Topic.create("Lattice table renamed",TableRenameListener.class);
    void tableRenamed(ConnectionConfig config,String database,String oldName,TableMetadata renamed);
}
