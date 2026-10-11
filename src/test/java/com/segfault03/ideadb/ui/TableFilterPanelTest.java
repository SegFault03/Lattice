package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class TableFilterPanelTest {
    @Test void applyAndClearStayTogetherAtNarrowWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel where = DatabaseUi.labeledInput(new JLabel("WHERE"), DatabaseInputs.textField(20));
            JPanel order = DatabaseUi.labeledInput(new JLabel("ORDER BY"), DatabaseInputs.textField(14));
            JButton apply = DatabaseUi.action("Apply", null, "Apply");
            JButton clear = DatabaseUi.action("Clear", null, "Clear");
            JPanel actions = DatabaseUi.group(apply, clear);
            TableFilterPanel panel = new TableFilterPanel(where, order, actions);
            int gap = com.intellij.util.ui.JBUI.scale(8);
            int twoRowWidth = order.getMinimumSize().width + actions.getPreferredSize().width + gap;
            int oneRowWidth = twoRowWidth + where.getMinimumSize().width + gap;
            // Exercise both sides of the wrap boundaries with the full action group.
            for (int width : new int[]{twoRowWidth - 1, twoRowWidth, oneRowWidth - 1, oneRowWidth, 1100}) {
                panel.setSize(width, 150);
                panel.doLayout();
                where.doLayout();
                order.doLayout();
                actions.doLayout();
                assertEquals(apply.getY(), clear.getY());
                assertTrue(clear.getX() + clear.getWidth() <= actions.getWidth());
                assertTrue(actions.getX() + actions.getWidth() <= width);
                if (width < twoRowWidth) {
                    assertTrue(where.getY() < order.getY() && order.getY() < actions.getY());
                } else if (width < oneRowWidth) {
                    assertTrue(where.getY() < order.getY());
                    assertEquals(order.getY() + order.getHeight() / 2, actions.getY() + actions.getHeight() / 2);
                } else {
                    assertEquals(where.getY() + where.getHeight() / 2, order.getY() + order.getHeight() / 2);
                    assertEquals(order.getY() + order.getHeight() / 2, actions.getY() + actions.getHeight() / 2);
                }
                assertTrue(((BorderLayout) where.getLayout()).getLayoutComponent(BorderLayout.CENTER).getWidth() >= 80);
                assertTrue(((BorderLayout) order.getLayout()).getLayoutComponent(BorderLayout.CENTER).getWidth() >= 80);
            }
        });
    }

    @Test void filtersStayCompactAndWrapWithoutClipping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel where = DatabaseUi.labeledInput(new JLabel("WHERE"), DatabaseInputs.textField(20));
            JPanel order = DatabaseUi.labeledInput(new JLabel("ORDER BY"), DatabaseInputs.textField(14));
            JButton apply = DatabaseUi.action("Apply", null, "Apply filter");
            TableFilterPanel panel = new TableFilterPanel(where, order, apply);
            int compactWidth = where.getPreferredSize().width + order.getPreferredSize().width
                    + apply.getPreferredSize().width + com.intellij.util.ui.JBUI.scale(16);
            for (int width : new int[]{1600, compactWidth, 450, 230, 1600}) {
                panel.setSize(width, 150);
                panel.doLayout();
                where.doLayout();
                order.doLayout();
                for (Component control : new Component[]{where, order, apply}) {
                    assertTrue(control.getX() >= 0);
                    assertTrue(control.getX() + control.getWidth() <= width);
                    assertTrue(control.getHeight() > 0);
                }
                if (width >= compactWidth) {
                    assertEquals(where.getPreferredSize().width, where.getWidth());
                    assertEquals(order.getPreferredSize().width, order.getWidth());
                    assertEquals(where.getY(), order.getY());
                    if (width == 1600) assertTrue(apply.getX() + apply.getWidth() < width / 2);
                } else if (width == 230) assertTrue(order.getY() > where.getY());
                else assertEquals(where.getY(), order.getY(), "Intermediate widths shrink fields before wrapping");
                for (JPanel group : new JPanel[]{where, order}) {
                    BorderLayout layout = (BorderLayout) group.getLayout();
                    Component label = layout.getLayoutComponent(BorderLayout.WEST);
                    Component field = layout.getLayoutComponent(BorderLayout.CENTER);
                    assertEquals(layout.getHgap(), field.getX() - label.getX() - label.getWidth());
                    assertTrue(field.getWidth() >= 80);
                }
            }
        });
    }
}
