package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.EnumSet;
import java.util.Set;

/** Filters source sections without discarding their loaded databases and tables. */
final class DatabaseSourceTreeModel extends DefaultTreeModel {
    private Set<DatabaseType> visibleTypes = EnumSet.allOf(DatabaseType.class);

    DatabaseSourceTreeModel(DefaultMutableTreeNode root) { super(root); }

    Set<DatabaseType> getVisibleTypes() { return Set.copyOf(visibleTypes); }

    void setVisibleTypes(Set<DatabaseType> types) {
        if (types.isEmpty()) throw new IllegalArgumentException("Select at least one data source");
        visibleTypes = EnumSet.copyOf(types);
        reload();
    }

    private boolean visible(Object node) {
        return node instanceof DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof TreeNodeData data
                && visibleTypes.contains(data.getDatabaseType());
    }

    @Override public Object getChild(Object parent, int index) {
        if (parent != getRoot()) return super.getChild(parent, index);
        int visibleIndex = 0;
        for (int i = 0; i < super.getChildCount(parent); i++) {
            Object child = super.getChild(parent, i);
            if (visible(child) && visibleIndex++ == index) return child;
        }
        throw new ArrayIndexOutOfBoundsException(index);
    }

    @Override public int getChildCount(Object parent) {
        if (parent != getRoot()) return super.getChildCount(parent);
        int count = 0;
        for (int i = 0; i < super.getChildCount(parent); i++) {
            if (visible(super.getChild(parent, i))) count++;
        }
        return count;
    }

    @Override public int getIndexOfChild(Object parent, Object child) {
        if (parent != getRoot()) return super.getIndexOfChild(parent, child);
        for (int i = 0; i < getChildCount(parent); i++) {
            if (getChild(parent, i) == child) return i;
        }
        return -1;
    }

    @Override public boolean isLeaf(Object node) {
        return node == getRoot() ? getChildCount(node) == 0 : super.isLeaf(node);
    }
}
