package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;

public class Icons {
    public static final Icon LATTICE = loadIcon("/icons/lattice.svg", AllIcons.Nodes.DataTables);
    public static final Icon LATTICE_LARGE = loadIcon("/icons/lattice_large.svg", AllIcons.Nodes.DataTables);
    public static final Icon DATABASE = loadIcon("/icons/database.svg", AllIcons.Nodes.DataTables);
    public static final Icon TABLE = loadIcon("/icons/table.svg", AllIcons.Nodes.DataTables);
    public static final Icon COLUMN = loadIcon("/icons/column.svg", AllIcons.Nodes.DataColumn);
    public static final Icon KEY = loadIcon("/icons/key.svg", AllIcons.Nodes.ResourceBundle);
    public static final Icon CONSOLE = loadIcon("/icons/console.svg", AllIcons.Actions.Execute);
    public static final Icon STOP = loadIcon("/icons/stop.svg", AllIcons.Actions.Cancel);
    public static final Icon CLEAR = loadIcon("/icons/clear.svg", AllIcons.Actions.Cancel);

    /** Shared SVG artwork at the appropriate size for source cards or menu entries. */
    public static Icon dataSource(DatabaseType type, int size) {
        Icon icon = loadIcon("/icons/source-" + type.name().toLowerCase(java.util.Locale.ROOT) + ".svg", DATABASE);
        return IconUtil.scale(icon, null, (float) JBUI.scale(size) / icon.getIconWidth());
    }

    private static Icon loadIcon(String path, Icon fallback) {
        try {
            Icon icon = IconLoader.getIcon(path, Icons.class);
            if (icon != null && icon.getIconWidth() > 0) {
                return icon;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
