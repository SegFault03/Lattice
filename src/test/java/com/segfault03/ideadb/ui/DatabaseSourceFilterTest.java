package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSourceFilterTest {
    @Test void selectingATypeWithoutConnectionsDoesNotCreateAnEmptySection() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(TreeNodeData.root());
        root.add(new DefaultMutableTreeNode(TreeNodeData.sourceType(DatabaseType.MYSQL)));
        DatabaseSourceTreeModel model = new DatabaseSourceTreeModel(root);
        model.setVisibleTypes(EnumSet.of(DatabaseType.POSTGRESQL));
        assertEquals(0, model.getChildCount(root));
        assertTrue(model.isLeaf(root));
        model.setVisibleTypes(EnumSet.allOf(DatabaseType.class));
        assertEquals(1, model.getChildCount(root));
        assertFalse(model.isLeaf(root));
    }

    @Test void filteringKeepsLoadedChildrenAndUsesVisibleIndices() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(TreeNodeData.root());
        for (DatabaseType type : List.of(DatabaseType.HSQLDB, DatabaseType.MYSQL, DatabaseType.POSTGRESQL)) {
            DefaultMutableTreeNode group = new DefaultMutableTreeNode(TreeNodeData.sourceType(type));
            root.add(group);
            TreeNodeData connection = TreeNodeData.connection(new ConnectionConfig(type, "Connection"), true);
            connection.setLoaded(true);
            group.add(new DefaultMutableTreeNode(connection));
        }
        DatabaseSourceTreeModel model = new DatabaseSourceTreeModel(root);
        assertEquals(3, model.getChildCount(root));
        Object hsql = root.getChildAt(0), mysql = root.getChildAt(1), postgres = root.getChildAt(2);
        Object loadedConnection = model.getChild(mysql, 0);
        model.setVisibleTypes(EnumSet.of(DatabaseType.MYSQL));
        assertEquals(1, model.getChildCount(root));
        assertSame(mysql, model.getChild(root, 0));
        assertEquals(0, model.getIndexOfChild(root, mysql));
        assertEquals(-1, model.getIndexOfChild(root, hsql));
        assertSame(loadedConnection, model.getChild(mysql, 0));
        model.setVisibleTypes(EnumSet.of(DatabaseType.MYSQL, DatabaseType.POSTGRESQL));
        assertEquals(2, model.getChildCount(root));
        assertSame(postgres, model.getChild(root, 1));
        assertEquals(1, model.getIndexOfChild(root, postgres));
        assertThrows(IllegalArgumentException.class, () -> model.setVisibleTypes(Set.of()));
        assertEquals(2, model.getChildCount(root));
        model.setVisibleTypes(EnumSet.allOf(DatabaseType.class));
        assertSame(hsql, model.getChild(root, 0));
        assertSame(loadedConnection, model.getChild(mysql, 0));
        assertTrue(((TreeNodeData) ((DefaultMutableTreeNode) loadedConnection).getUserObject()).isLoaded());
    }

    @Test void pickerSupportsMultipleTypesAllAndCancelWithoutApplyingAnEmptySelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<Set<DatabaseType>> applied = new AtomicReference<>();
            DatabaseSourceFilterPopup picker = new DatabaseSourceFilterPopup(
                    List.of(DatabaseType.HSQLDB, DatabaseType.MYSQL, DatabaseType.POSTGRESQL),
                    EnumSet.allOf(DatabaseType.class), applied::set, () -> {});
            List<AbstractButton> buttons = buttons(picker);
            JCheckBox all = (JCheckBox) named(buttons, "All data sources");
            JCheckBox mysql = (JCheckBox) named(buttons, "MySQL");
            JCheckBox postgres = (JCheckBox) named(buttons, "PostgreSQL");
            AbstractButton apply = named(buttons, "Apply");
            assertTrue(all.isSelected());
            all.doClick(0);
            assertFalse(apply.isEnabled(), "An empty view cannot be applied");
            mysql.doClick(0);
            assertTrue(apply.isEnabled());
            assertNull(applied.get(), "Checkbox changes are only a draft until Apply");
            apply.doClick(0);
            assertEquals(Set.of(DatabaseType.MYSQL), applied.get());
            postgres.doClick(0);
            apply.doClick(0);
            assertEquals(Set.of(DatabaseType.MYSQL, DatabaseType.POSTGRESQL), applied.get());
            all.doClick(0);
            apply.doClick(0);
            assertEquals(EnumSet.allOf(DatabaseType.class), applied.get());
            mysql.doClick(0);
            named(buttons, "Cancel").doClick(0);
            assertEquals(EnumSet.allOf(DatabaseType.class), applied.get(), "Cancel leaves the applied view unchanged");
        });
    }

    private static AbstractButton named(List<AbstractButton> buttons, String name) {
        return buttons.stream().filter(button -> name.equals(button.getAccessibleContext().getAccessibleName()))
                .findFirst().orElseThrow();
    }

    private static List<AbstractButton> buttons(Container parent) {
        List<AbstractButton> result = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            if (component instanceof AbstractButton button) result.add(button);
            else if (component instanceof Container container) result.addAll(buttons(container));
        }
        return result;
    }
}
