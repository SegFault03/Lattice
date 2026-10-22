package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.util.IconUtil;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;

public class Icons {
    private static final java.util.Map<DatabaseType, Icon> TABLE_SOURCE_ICONS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Icon LATTICE = loadIcon("/icons/lattice.svg", AllIcons.Nodes.DataTables);
    public static final Icon LATTICE_LARGE = loadIcon("/icons/lattice_large.svg", AllIcons.Nodes.DataTables);
    public static final Icon DATABASE = loadIcon("/icons/database.svg", AllIcons.Nodes.DataTables);
    public static final Icon PLUG_CONNECTED = loadIcon("/icons/plug-connected.svg", AllIcons.General.GreenCheckmark);
    public static final Icon PLUG_DISCONNECTED = loadIcon("/icons/plug-disconnected.svg", AllIcons.Actions.OfflineMode);
    public static final Icon TABLE = loadIcon("/icons/table.svg", AllIcons.Nodes.DataTables);
    public static final Icon COLUMN = loadIcon("/icons/column.svg", AllIcons.Nodes.DataColumn);
    public static final Icon KEY = loadIcon("/icons/key.svg", AllIcons.Nodes.ResourceBundle);
    public static final Icon FOREIGN_KEY = loadIcon("/icons/foreign-key.svg", COLUMN);
    public static final Icon PRIMARY_FOREIGN_KEY = new com.intellij.ui.RowIcon(KEY, FOREIGN_KEY);
    public static final Icon CONSOLE = loadIcon("/icons/console.svg", AllIcons.Actions.Execute);
    public static final Icon STOP = loadIcon("/icons/stop.svg", AllIcons.Actions.Cancel);
    public static final Icon CLEAR = loadIcon("/icons/clear.svg", AllIcons.Actions.Cancel);

    /** Shared SVG artwork at the appropriate size for source cards or menu entries. */
    public static Icon dataSource(DatabaseType type, int size) {
        Icon icon = loadIcon("/icons/source-" + type.name().toLowerCase(java.util.Locale.ROOT) + ".svg", DATABASE);
        // All source SVGs have a 28px view box. Lazy platform icons may report a
        // placeholder width before loading, so deriving the ratio from that width can overscale them.
        return IconUtil.scale(icon, null, size / 28f);
    }

    /** Standard table tab glyph with the source SVG overlapping its bottom-left corner. */
    public static Icon tableWithDataSource(DatabaseType type) {
        return TABLE_SOURCE_ICONS.computeIfAbsent(type, source -> {
            // Opaque badges keep table grid lines from showing through the source artwork.
            String name = source.name().toLowerCase(java.util.Locale.ROOT);
            String suffix = source == DatabaseType.HSQLDB ? ".svg" : "-badge.svg";
            Icon artwork = loadIcon("/icons/source-" + name + suffix, null);
            Icon badge = artwork == null ? dataSource(source, 10) : IconUtil.scale(artwork, null, 10 / 28f);
            com.intellij.ui.LayeredIcon icon = new com.intellij.ui.LayeredIcon(2);
            icon.setIcon(TABLE, 0);
            icon.setIcon(badge, 1, 0, TABLE.getIconHeight() - badge.getIconHeight());
            return icon;
        });
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
