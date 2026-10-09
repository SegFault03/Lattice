package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Fixed-size explorer actions grouped like an IntelliJ tool window toolbar. */
final class DatabaseExplorerToolbar extends JPanel {
    private final JButton add;
    private final JButton edit;
    private final JButton remove;
    private final JButton refresh;
    private final JButton console;
    private boolean hasConnections;
    private boolean busy;
    private TreeNodeData selection;

    DatabaseExplorerToolbar(Consumer<Component> addConnection, Runnable editConnection,
                            Runnable removeConnection, Runnable refreshConnection,
                            Runnable openConsole, Runnable showHelp) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                JBUI.Borders.empty(6, 12)));
        add = button(AllIcons.General.Add, "Add connection…", null, true);
        add.addActionListener(e -> addConnection.accept(add));
        edit = button(AllIcons.General.Settings, "Edit connection…", editConnection);
        remove = button(AllIcons.General.Remove, "Remove connection settings", removeConnection);
        separator();
        refresh = button(AllIcons.Actions.Refresh, "Refresh", refreshConnection);
        console = button(Icons.CONSOLE, "Open SQL Console", openConsole);
        separator();
        button(AllIcons.General.ContextHelp, "Welcome to Lattice", showHelp);
        setHasConnections(false);
    }

    void setHasConnections(boolean hasConnections) {
        this.hasConnections = hasConnections;
        updateActions();
    }

    void setSelection(TreeNodeData selection) {
        this.selection = selection;
        updateActions();
    }

    void setBusy(boolean busy) { this.busy = busy; updateActions(); }

    private void updateActions() {
        add.setEnabled(!busy);
        boolean connectionSelected = !busy && hasConnections && selection != null && selection.getConnectionConfig() != null;
        edit.setEnabled(connectionSelected);
        remove.setEnabled(connectionSelected && selection.getType() == TreeNodeData.NodeType.CONNECTION);
        refresh.setEnabled(!busy && hasConnections && (selection == null || switch (selection.getType()) {
            case ROOT, CONNECTION, DATABASE, TABLE, VIEW -> true;
            default -> false;
        }));
        console.setEnabled(!busy && hasConnections);
    }

    private JButton button(Icon icon, String tooltip, Runnable action) {
        return button(icon, tooltip, action, false);
    }

    private JButton button(Icon icon, String tooltip, Runnable action, boolean dropdown) {
        JButton button = new ToolbarButton(icon, dropdown);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFocusable(false);
        if (action != null) button.addActionListener(e -> action.run());
        add(button);
        add(Box.createHorizontalStrut(JBUI.scale(2)));
        return button;
    }

    private void separator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL) {
            @Override protected void paintComponent(Graphics graphics) {
                graphics.setColor(getForeground());
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        separator.setForeground(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR);
        separator.setPreferredSize(JBUI.size(1, 18));
        separator.setMinimumSize(JBUI.size(1, 18));
        separator.setMaximumSize(JBUI.size(1, 18));
        add(Box.createHorizontalStrut(JBUI.scale(4)));
        add(separator);
        add(Box.createHorizontalStrut(JBUI.scale(4)));
    }

    private static final class ToolbarButton extends JButton {
        private final boolean dropdown;

        private ToolbarButton(Icon icon, boolean dropdown) {
            super(icon);
            this.dropdown = dropdown;
            // These native glyphs are monochrome. Dimming preserves their outlines
            // instead of flattening the console icon's background into a solid block.
            setDisabledIcon(IconLoader.getTransparentIcon(icon, 0.4f));
            setBorder(JBUI.Borders.empty());
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setPreferredSize(JBUI.size(28, 28));
            setMinimumSize(JBUI.size(28, 28));
            setMaximumSize(JBUI.size(28, 28));
            setAlignmentY(Component.CENTER_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics graphics) {
            ButtonModel model = getModel();
            if (isEnabled() && (model.isRollover() || model.isPressed() && model.isArmed())) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(model.isPressed() && model.isArmed()
                            ? JBUI.CurrentTheme.ActionButton.pressedBackground()
                            : JBUI.CurrentTheme.ActionButton.hoverBackground());
                    int arc = JBUI.scale(6);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g.dispose();
                }
            }
            // Paint only the centered icon; regular button UIs can otherwise add
            // their own background or focus outline over the toolbar state.
            Icon icon = isEnabled() ? getIcon() : getDisabledIcon();
            if (icon != null) {
                icon.paintIcon(this, graphics, (getWidth() - icon.getIconWidth()) / 2,
                        (getHeight() - icon.getIconHeight()) / 2);
            }
            if (dropdown) {
                Icon arrow = AllIcons.General.ButtonDropTriangle;
                arrow.paintIcon(this, graphics, getWidth() - arrow.getIconWidth() - JBUI.scale(2),
                        getHeight() - arrow.getIconHeight() - JBUI.scale(2));
            }
        }
    }
}
