package com.segfault03.ideadb.ui;

import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Centered entry point for creating the first database connection. */
final class EmptyConnectionPanel extends JPanel {
    EmptyConnectionPanel(Runnable addConnection) {
        super(new GridBagLayout());
        GridBagConstraints prompt = new GridBagConstraints();
        prompt.gridx = 0;
        prompt.gridy = 0;
        add(new JBLabel("No database connections"), prompt);
        prompt.gridy = 1;
        prompt.insets = JBUI.insetsTop(8);
        ActionLink link = new ActionLink("Add a connection…");
        // Dialog focus restoration otherwise leaves the link's blue focus border visible
        // after a mouse click. Keep that indicator for keyboard traversal only.
        link.setFocusPainted(false);
        link.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                link.setFocusPainted(switch (event.getCause()) {
                    case TRAVERSAL, TRAVERSAL_FORWARD, TRAVERSAL_BACKWARD, TRAVERSAL_UP, TRAVERSAL_DOWN -> true;
                    default -> false;
                });
            }

            @Override public void focusLost(FocusEvent event) {
                link.setFocusPainted(false);
            }
        });
        link.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                link.setFocusPainted(false);
            }
        });
        link.addActionListener(e -> addConnection.run());
        add(link, prompt);
    }
}
