package com.segfault03.ideadb.ui;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Keeps Apply beside the fields when possible, using two balanced rows before stacking. */
final class TableFilterPanel extends JPanel {
    private final JComponent where, order;
    private final JButton apply;
    private final JPanel remainder = new JPanel();
    private int rows;
    private boolean shrinking;

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
        remainder.setOpaque(false);
        remainder.setPreferredSize(new Dimension());
        remainder.setMinimumSize(new Dimension());
        add(remainder);
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
        boolean shrink = next == 1 && width > 0
                && width < where.getPreferredSize().width + order.getPreferredSize().width + actionWidth + 2 * gap;
        if (next == rows && shrink == shrinking) return;
        rows = next;
        shrinking = shrink;
        GridBagLayout layout = (GridBagLayout) getLayout();
        layout.setConstraints(where, cell(0, 0, rows == 1 ? 1 : 2, shrink ? .6 : 0, rows == 1 ? gap : 0));
        layout.setConstraints(order, cell(rows == 1 ? 1 : 0, rows == 1 ? 0 : 1, rows == 3 ? 2 : 1, shrink ? .4 : 0, rows == 3 ? 0 : gap));
        layout.setConstraints(apply, cell(rows == 1 ? 2 : rows == 2 ? 1 : 0, rows == 1 ? 0 : rows == 2 ? 1 : 2, 1, 0, 0));
        GridBagConstraints trailing = cell(rows == 1 ? 3 : 2, 0, 1, shrink ? 0 : 1, 0);
        trailing.gridheight = rows;
        layout.setConstraints(remainder, trailing);
    }

    private static GridBagConstraints cell(int column, int row, int span, double weight, int rightGap) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = column;
        cell.gridy = row;
        cell.gridwidth = span;
        cell.weightx = weight;
        cell.anchor = GridBagConstraints.WEST;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.insets = JBUI.insets(row > 0 ? 4 : 0, 0, 0, 0);
        cell.insets.right = rightGap;
        return cell;
    }
}
