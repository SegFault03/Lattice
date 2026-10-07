package com.segfault03.ideadb.dialog;

import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.DatabaseInputs;

import javax.swing.*;
import java.awt.*;

/** Shared label column and top-aligned rows for every connection form and card. */
final class ConnectionFormPanel extends JPanel {
    ConnectionFormPanel() {
        super(new GridBagLayout());
        GridBagLayout layout = (GridBagLayout) getLayout();
        // Include the widest labels used by any nested card, using the rendered copy.
        // Otherwise GridBagLayout can expand one card's label column independently.
        int labelWidth = 0;
        for (String label : new String[]{"Database name:", "Database type:", "Database path:"}) {
            labelWidth = Math.max(labelWidth, new JLabel(label).getPreferredSize().width);
        }
        labelWidth += JBUI.scale(10);
        layout.columnWidths = new int[]{labelWidth, 0};
        layout.columnWeights = new double[]{0, 1};

        GridBagConstraints space = new GridBagConstraints();
        space.gridx = 0;
        space.gridy = 99;
        space.gridwidth = 2;
        space.weighty = 1;
        space.fill = GridBagConstraints.BOTH;
        add(Box.createVerticalGlue(), space);
    }

    void addRow(int row, String label, JComponent input, boolean stretch) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridy = row;
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = JBUI.insets(3, 0, 3, 10);
        cell.gridx = 0;
        JLabel fieldLabel = new JLabel(label);
        fieldLabel.setLabelFor(input);
        add(fieldLabel, cell);

        cell.gridx = 1;
        cell.weightx = 1;
        cell.insets = JBUI.insets(3, 0);
        cell.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        add(input, cell);
    }

    void addSection(int row, String title) {
        addFullWidthRow(row, DatabaseUi.section(title));
    }

    void addFullWidthRow(int row, JComponent content) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.gridy = row;
        cell.gridwidth = 2;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        add(content, cell);
    }

    static <T extends JComponent> T width(T component, int width) {
        DatabaseInputs.style(component);
        Dimension size = component.getPreferredSize();
        size.width = JBUI.scale(width);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        return component;
    }

}
