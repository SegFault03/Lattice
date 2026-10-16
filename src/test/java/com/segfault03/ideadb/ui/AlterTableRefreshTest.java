package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import javax.swing.tree.DefaultMutableTreeNode;
import static org.junit.jupiter.api.Assertions.*;

class AlterTableRefreshTest {
    @Test void successfulAlterRefreshesTheSchemaRatherThanNestingATablesFolder() {
        var config = new ConnectionConfig(DatabaseType.HSQLDB,"test");
        var root = new DefaultMutableTreeNode(TreeNodeData.root());
        var connection = new DefaultMutableTreeNode(TreeNodeData.connection(config,true)); root.add(connection);
        var schema = new DefaultMutableTreeNode(TreeNodeData.database(config,"PUBLIC")); connection.add(schema);
        var folder = new DefaultMutableTreeNode(TreeNodeData.tablesFolder(config,"PUBLIC",1)); schema.add(folder);
        var table = new DefaultMutableTreeNode(TreeNodeData.table(config,"PUBLIC",new TableMetadata(null,"PUBLIC","customers","TABLE"))); folder.add(table);
        assertSame(schema,DatabaseMainPanel.databaseAncestor(table));
        assertSame(schema,DatabaseMainPanel.databaseAncestor(folder));
        assertSame(schema,DatabaseMainPanel.databaseAncestor(schema));
        assertNotSame(folder,DatabaseMainPanel.databaseAncestor(table));
    }
    @Test void detachedNodesAndConnectionNodesDoNotBecomeRefreshTargets() {
        var config = new ConnectionConfig(DatabaseType.MYSQL,"test");
        assertNull(DatabaseMainPanel.databaseAncestor(new DefaultMutableTreeNode(TreeNodeData.tablesFolder(config,"shop",1))));
        assertNull(DatabaseMainPanel.databaseAncestor(new DefaultMutableTreeNode(TreeNodeData.connection(config,true))));
    }
}
