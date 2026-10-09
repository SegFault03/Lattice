package com.segfault03.ideadb.ui;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Keeps Apply beside the fields when possible, using two balanced rows before stacking. */
final class TableFilterPanel extends JPanel {
    private final JComponent where, order;
    private final JButton apply;
    private int rows;

    TableFilterPanel(JComponent where, JComponent order, JButton apply) {
        super(new GridBagLayout());
        this.where = where;
        this.order = order;
        this.apply = apply;
        for (JComponent group : new JComponent[]{where, order}) {
            BorderLayout layout = (BorderLayout) group.getLayout();
            Component label = layout.getLayoutComponent(BorderLayout.WEST);
            group.setMinimumSize(new Dimension(label.getPreferredSize().width + layout.getHgap() + JBUI.scale(80),
                    group.getPreferredSize().height));
            add(group);
        }
        add(apply);
    }

    @Override public Dimension getPreferredSize() { configure(); return super.getPreferredSize(); }
    @Override public Dimension getMinimumSize() { configure(); return super.getMinimumSize(); }
    @Override public void doLayout() { configure(); super.doLayout(); }

    private void configure() {
        Insets insets = getInsets();
        int width = getWidth() - insets.left - insets.right;
        int gap = JBUI.scale(8);
        int actionWidth = apply.getPreferredSize().width;
        int next = width <= 0 || width >= where.getMinimumSize().width + order.getMinimumSize().width + actionWidth + 2 * gap
                ? 1 : width >= order.getMinimumSize().width + actionWidth + gap ? 2 : 3;
        if (next == rows) return;
        rows = next;
        GridBagLayout layout = (GridBagLayout) getLayout();
        layout.setConstraints(where, cell(0, 0, rows == 1 ? 1 : 2, .6, rows == 1 ? gap : 0));
        layout.setConstraints(order, cell(rows == 1 ? 1 : 0, rows == 1 ? 0 : 1, rows == 3 ? 2 : 1, .4, rows == 3 ? 0 : gap));
        layout.setConstraints(apply, cell(rows == 1 ? 2 : rows == 2 ? 1 : 0, rows == 1 ? 0 : rows == 2 ? 1 : 2, 1, 0, 0));
    }

    private static GridBagConstraints cell(int column, int row, int span, double weight, int rightGap) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = column;
        cell.gridy = row;
        cell.gridwidth = span;
        cell.weightx = weight;
        cell.anchor = GridBagConstraints.WEST;
        cell.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        cell.insets = JBUI.insets(row > 0 ? 4 : 0, 0, 0, 0);
        cell.insets.right = rightGap;
        return cell;
    }
}
