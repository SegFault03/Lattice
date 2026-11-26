package com.segfault03.ideadb.ui;

import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** First run: the explorer itself is home. One link per data source opens the connection dialog for it. */
final class EmptyConnectionPanel extends JPanel {
    EmptyConnectionPanel(Consumer<DatabaseType> addConnection) {
        super(new BorderLayout());
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(JBUI.Borders.empty(24, 16));

        JBLabel heading = new JBLabel("No connections yet");
        heading.setFont(JBFont.label().asBold());
        stack(content, heading);
        content.add(Box.createVerticalStrut(JBUI.scale(4)));
        WrappingLabel hint = new WrappingLabel("Recent tables, consoles and scripts will appear here and in Search Everywhere.");
        hint.setForeground(DatabaseUi.SECONDARY_TEXT);
        stack(content, hint);
        content.add(Box.createVerticalStrut(JBUI.scale(12)));
        for (DatabaseType type : DatabaseType.values()) {
            ActionLink link = new ActionLink(type.getDisplayName() + "…");
            link.setIcon(Icons.dataSource(type, 16));
            link.setToolTipText("New " + type.getDisplayName() + " connection");
            keyboardOnlyFocusBorder(link);
            link.addActionListener(event -> addConnection.accept(type));
            stack(content, link);
            content.add(Box.createVerticalStrut(JBUI.scale(8)));
        }
        add(content, BorderLayout.NORTH);
    }

    private static void stack(JPanel column, JComponent child) {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(child);
    }

    /** Dialog focus restoration otherwise leaves the link's focus border visible after a mouse click. */
    private static void keyboardOnlyFocusBorder(ActionLink link) {
        link.setFocusPainted(false);
        link.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                link.setFocusPainted(switch (event.getCause()) {
                    case TRAVERSAL, TRAVERSAL_FORWARD, TRAVERSAL_BACKWARD, TRAVERSAL_UP, TRAVERSAL_DOWN -> true;
                    default -> false;
                });
            }
            @Override public void focusLost(FocusEvent event) { link.setFocusPainted(false); }
        });
        link.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { link.setFocusPainted(false); }
        });
    }
}
