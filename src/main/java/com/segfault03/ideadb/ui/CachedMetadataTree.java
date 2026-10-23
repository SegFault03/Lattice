package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;

import javax.swing.tree.DefaultMutableTreeNode;

/** Restores a browsable tree without requesting a live connection. */
final class CachedMetadataTree {
    private CachedMetadataTree() { }

    static void restore(DefaultMutableTreeNode connection, ExplorerMetadataSnapshot snapshot) {
        var source = (TreeNodeData) connection.getUserObject();
        ConnectionConfig config = source.getConnectionConfig();
        connection.removeAllChildren();
        source.setConnected(false);
        source.setLoaded(snapshot != null);
        if (snapshot == null) {
            connection.add(new DefaultMutableTreeNode(TreeNodeData.placeholder("")));
            return;
        }
        for (var namespace : snapshot.namespaces()) {
            var database = add(connection, TreeNodeData.database(config, namespace.name()));
            // Cached branches are fully browsable offline, including incomplete/empty snapshots.
            DefaultMutableTreeNode tables = null, views = null;
            for (var table : namespace.tables()) {
                var metadata = table.metadata();
                DefaultMutableTreeNode folder;
                if (metadata.isView()) {
                    if (views == null) views = add(database, TreeNodeData.viewsFolder(config, namespace.name(), 0));
                    folder = views;
                } else {
                    if (tables == null) tables = add(database, TreeNodeData.tablesFolder(config, namespace.name(), 0));
                    folder = tables;
                }
                var node = add(folder, TreeNodeData.table(config, namespace.name(), metadata));
                if (table.columnsLoaded()) {
                    var columns = add(node, TreeNodeData.columnsFolder(config, namespace.name(), metadata, table.columns().size()));
                    for (var column : table.columns()) add(columns, TreeNodeData.column(config, namespace.name(), metadata, column));
                }
            }
            if (tables == null && namespace.tablesLoaded()) tables = add(database, TreeNodeData.tablesFolder(config, namespace.name(), 0));
            if (tables != null) ((TreeNodeData) tables.getUserObject()).setName("Tables (" + tables.getChildCount() + ")");
            if (views != null) ((TreeNodeData) views.getUserObject()).setName("Views (" + views.getChildCount() + ")");
        }
    }

    private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, TreeNodeData data) {
        data.setCached(true);
        data.setLoaded(true);
        var node = new DefaultMutableTreeNode(data);
        parent.add(node);
        return node;
    }
}
