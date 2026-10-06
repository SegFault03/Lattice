package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/** Contextual hint shown only when a table is selected. */
final class DatabaseExplorerHint extends JPanel {
    private final JBLabel label = new JBLabel("Double-click table to open in editor tab");

    DatabaseExplorerHint() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                JBUI.Borders.empty(6, 12)));
        label.setFont(label.getFont().deriveFont(11f));
        label.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        add(label, BorderLayout.CENTER);
        setVisible(false);
    }

    void setSelection(TreeNodeData selection) {
        boolean table = selection != null && selection.getType() == TreeNodeData.NodeType.TABLE;
        setVisible(table);
        revalidate();
        repaint();
    }
}
