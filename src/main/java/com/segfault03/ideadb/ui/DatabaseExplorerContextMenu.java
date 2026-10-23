package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.segfault03.ideadb.provider.DatabaseFeature;
import com.segfault03.ideadb.provider.DatabaseProviders;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** One availability policy for the explorer's native context menus. */
final class DatabaseExplorerContextMenu {
    enum Action {
        CONNECT(false), DISCONNECT(true), CONSOLE(true), CREATE_NAMESPACE(true),
        EDIT_CONNECTION(false), REMOVE_CONNECTION(false), CREATE_TABLE(true), DROP_NAMESPACE(true),
        REFRESH_TABLES(false), OPEN_TABLE(true), ALTER_TABLE(true), TRUNCATE_TABLE(true), DROP_TABLE(true),
        GENERATE_SELECT(false), GENERATE_INSERT(false);

        private final boolean requiresConnection;
        Action(boolean requiresConnection) { this.requiresConnection = requiresConnection; }
        boolean requiresConnection() { return requiresConnection; }
    }

    private DatabaseExplorerContextMenu() { }

    static JPopupMenu create(TreeNodeData data, boolean connected, Consumer<Action> perform) {
        var menu = DatabaseUi.popupMenu();
        if (data.getConnectionConfig() == null) return menu;
        var provider = DatabaseProviders.get(data.getConnectionConfig().getType());
        var features = provider.features();
        Consumer<Action> guarded = action -> {
            if (connected || !action.requiresConnection()) perform.accept(action);
        };
        switch (data.getType()) {
            case CONNECTION -> {
                add(menu, connected ? "Refresh connection" : "Connect", AllIcons.Actions.Refresh, Action.CONNECT, true, connected, guarded);
                add(menu, "Disconnect", null, Action.DISCONNECT, true, connected, guarded);
                menu.addSeparator();
                add(menu, "Open SQL console", Icons.CONSOLE, Action.CONSOLE, true, connected, guarded);
                add(menu, "Create " + provider.namespaceLabel() + "…", null, Action.CREATE_NAMESPACE,
                        features.contains(DatabaseFeature.CREATE_NAMESPACE), connected, guarded);
                menu.addSeparator();
                add(menu, "Edit connection…", AllIcons.Actions.Edit, Action.EDIT_CONNECTION, true, connected, guarded);
                add(menu, "Remove connection", AllIcons.General.Remove, Action.REMOVE_CONNECTION, true, connected, guarded);
            }
            case DATABASE -> {
                add(menu, "Open SQL console", Icons.CONSOLE, Action.CONSOLE, true, connected, guarded);
                add(menu, "Create table…", Icons.TABLE, Action.CREATE_TABLE, features.contains(DatabaseFeature.CREATE_TABLE), connected, guarded);
                add(menu, "Drop " + provider.namespaceLabel() + "…", null, Action.DROP_NAMESPACE,
                        features.contains(DatabaseFeature.DROP_NAMESPACE), connected, guarded);
                menu.addSeparator();
                add(menu, "Refresh tables", AllIcons.Actions.Refresh, Action.REFRESH_TABLES, true, connected, guarded);
            }
            case TABLE, VIEW -> {
                var open = add(menu, "Open data editor", AllIcons.Actions.Preview, Action.OPEN_TABLE, true, connected, guarded);
                open.setFont(open.getFont().deriveFont(Font.BOLD));
                add(menu, "Open in SQL console", Icons.CONSOLE, Action.CONSOLE, true, connected, guarded);
                if (!data.getTableMetadata().isView()) {
                    menu.addSeparator();
                    add(menu, "Alter table…", AllIcons.Actions.Edit, Action.ALTER_TABLE, features.contains(DatabaseFeature.MODIFY_COLUMN), connected, guarded);
                    add(menu, "Truncate table…", AllIcons.General.Remove, Action.TRUNCATE_TABLE, features.contains(DatabaseFeature.TRUNCATE_TABLE), connected, guarded);
                    add(menu, "Drop table…", AllIcons.General.Remove, Action.DROP_TABLE, features.contains(DatabaseFeature.DROP_TABLE), connected, guarded);
                }
                menu.addSeparator();
                var generate = DatabaseUi.submenu("Generate SQL");
                add(generate.getPopupMenu(), "SELECT Statement", null, Action.GENERATE_SELECT, true, connected, guarded);
                add(generate.getPopupMenu(), "INSERT Statement", null, Action.GENERATE_INSERT,
                        connected || !data.getTableMetadata().getColumns().isEmpty(), connected, guarded);
                menu.add(generate);
            }
            default -> { }
        }
        return menu;
    }

    private static JMenuItem add(JPopupMenu menu, String title, Icon icon, Action action, boolean supported,
                                 boolean connected, Consumer<Action> perform) {
        var item = new JMenuItem(title, icon);
        if (icon != null) item.setDisabledIcon(com.intellij.openapi.util.IconLoader.getTransparentIcon(icon, 0.4f));
        item.setEnabled(supported && (connected || !action.requiresConnection()));
        if (!connected && action.requiresConnection()) item.setToolTipText("Connect to the database to use this action");
        item.addActionListener(event -> perform.accept(action));
        menu.add(item);
        return item;
    }
}
