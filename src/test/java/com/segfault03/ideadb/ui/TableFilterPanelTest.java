package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class TableFilterPanelTest {
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
