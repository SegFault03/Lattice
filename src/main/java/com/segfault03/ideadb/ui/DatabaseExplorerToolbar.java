package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Compact explorer actions with a disabled state when there are no connections. */
final class DatabaseExplorerToolbar extends JPanel {
    private final JButton edit;
    private final JButton remove;
    private final JButton refresh;
    private final JButton console;

    DatabaseExplorerToolbar(Consumer<Component> addConnection, Runnable editConnection,
                            Runnable removeConnection, Runnable refreshConnection,
                            Runnable openConsole, Runnable showHelp) {
        super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2)));
        JButton add = button(AllIcons.General.Add, "Add Database Connection", null);
        add.addActionListener(e -> addConnection.accept(add));
        edit = button(AllIcons.Actions.Edit, "Edit Connection Properties", editConnection);
        remove = button(AllIcons.General.Remove, "Remove Connection", removeConnection);
        separator();
        refresh = button(AllIcons.Actions.Refresh, "Refresh", refreshConnection);
        console = button(Icons.CONSOLE, "Open SQL Console", openConsole);
        separator();
        button(AllIcons.General.ContextHelp, "Lattice Welcome & Tips", showHelp);
        setHasConnections(false);
    }

    void setHasConnections(boolean hasConnections) {
        for (JButton action : new JButton[]{edit, remove, refresh, console}) {
            action.setEnabled(hasConnections);
        }
    }

    private JButton button(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(JBUI.insets(2));
        button.setPreferredSize(JBUI.size(26, 26));
        button.setMinimumSize(JBUI.size(26, 26));
        button.setMaximumSize(JBUI.size(26, 26));
        if (action != null) button.addActionListener(e -> action.run());
        add(button);
        return button;
    }

    private void separator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(JBUI.size(1, 20));
        add(separator);
    }
}
