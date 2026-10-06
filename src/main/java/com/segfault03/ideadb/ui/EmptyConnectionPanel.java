package com.segfault03.ideadb.ui;

import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

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
        link.addActionListener(e -> addConnection.run());
        add(link, prompt);
    }
}
