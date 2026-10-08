package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.RowIcon;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public class DatabaseTreeCellRenderer extends ColoredTreeCellRenderer {

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
        setToolTipText(null);
        getAccessibleContext().setAccessibleDescription(null);
        if (!(value instanceof DefaultMutableTreeNode)) {
            return;
        }

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        Object userObject = node.getUserObject();

        if (!(userObject instanceof TreeNodeData)) {
            append(value.toString());
            return;
        }

        TreeNodeData data = (TreeNodeData) userObject;

        switch (data.getType()) {
            case ROOT:
                setIcon(AllIcons.Nodes.Folder);
                append(data.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                break;

            case CONNECTION:
                String product = data.getConnectionConfig() == null ? "" : " · " + data.getConnectionConfig().getType().getDisplayName();
                setToolTipText(data.getName() + product + (data.isConnected()
                        ? " · Connected · Expand to browse databases" : " · Disconnected · Expand to connect"));
                setIcon(new RowIcon(Icons.DATABASE, data.isConnected() ? AllIcons.General.GreenCheckmark : AllIcons.Actions.OfflineMode));
                append(data.getName(), data.isConnected() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                getAccessibleContext().setAccessibleDescription(getToolTipText());
                break;

            case DATABASE:
                setIcon(AllIcons.Nodes.DataTables);
                append(data.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                break;

            case TABLES_FOLDER:
            case VIEWS_FOLDER:
            case COLUMNS_FOLDER:
                setIcon(expanded ? AllIcons.Nodes.Folder : AllIcons.Nodes.Folder);
                append(data.getName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                break;

            case TABLE:
                setIcon(Icons.TABLE);
                append(data.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                break;

            case VIEW:
                setIcon(Icons.TABLE);
                append(data.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" (view)", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                break;

            case COLUMN:
                if (data.getColumnMetadata() != null) {
                    var col = data.getColumnMetadata();
                    setToolTipText(col.getName() + " · " + col.getFormattedType()
                            + (col.isPrimaryKey() ? " · Primary key" : "")
                            + (col.isNullable() ? " · Allows NULL" : " · Required")
                            + (col.isAutoIncrement() ? " · Auto-increment" : ""));
                    if (col.isPrimaryKey()) {
                        setIcon(Icons.KEY);
                        append(col.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    } else {
                        setIcon(Icons.COLUMN);
                        append(col.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    }

                    append(" : " + col.getFormattedType(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (col.isPrimaryKey()) {
                        append(" PK", SimpleTextAttributes.DARK_TEXT);
                    }
                    if (!col.isNullable()) {
                        append(" NOT NULL", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                    if (col.isAutoIncrement()) {
                        append(" Auto", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    }
                } else {
                    setIcon(Icons.COLUMN);
                    append(data.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                }
                break;

            case ERROR:
                setIcon(AllIcons.General.Error);
                append(data.getName(), SimpleTextAttributes.ERROR_ATTRIBUTES);
                setToolTipText(data.getErrorDetail());
                break;

            case LOADING:
                setIcon(AllIcons.Process.Step_passive);
                append(data.getName(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES);
                break;
        }
    }
}
