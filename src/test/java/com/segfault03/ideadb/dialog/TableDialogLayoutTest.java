package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.ui.DatabaseUi;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableDialogLayoutTest {
    @Test void nativeButtonMarginsDoNotConsumeActionTextSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var button = DatabaseUi.action("Apply changes", UIManager.getIcon("OptionPane.informationIcon"), "Apply changes");
            assertEquals(new Insets(0, 0, 0, 0), button.getMargin());
            for (float size : new float[]{13, 16.25f, 19.5f}) {
                button.setFont(button.getFont().deriveFont(size));
                button.setSize(button.getPreferredSize());
                assertFullText(button, button.getText(), button.getIcon(), button.getIconTextGap(), new Insets(0,0,0,0));
            }
        });
    }

    @Test void bothDialogsKeepTextAfterFontGrowthAndResize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "Local development");
            var alter = new AlterTablePanel(config, "PUBLIC", "WARMUP_ORDERS", List.of(
                    new ColumnMetadata("QUANTITY", "INTEGER", Types.INTEGER, 32, 0, false, false, false, null)));
            var create = new CreateTablePanel(config, "PUBLIC");
            for (JPanel panel : List.of(alter, create)) {
                var fonts = new java.util.IdentityHashMap<Component, Font>();
                for (Component child : descendants(panel)) if (child.getFont() != null) fonts.put(child, child.getFont());
                for (float scale : new float[]{1, 1.25f, 1.5f, 1}) {
                    // Realize once before changing fonts, so stale BoxLayout caches are exercised.
                    panel.setSize(panel.getPreferredSize()); layout(panel);
                    fonts.forEach((child, font) -> child.setFont(font.deriveFont(font.getSize2D() * scale)));
                    for (int width : new int[]{panel.getMinimumSize().width, panel.getPreferredSize().width, 1200}) {
                        panel.setSize(width, panel.getPreferredSize().height);
                        for (int pass = 0; pass < 4; pass++) layout(panel);
                        for (Component child : descendants(panel)) {
                            if (!visible(child, panel)) continue;
                            if (child instanceof JLabel label && label.getText() != null && !label.getText().isEmpty())
                                assertFullText(label, label.getText(), label.getIcon(), label.getIconTextGap(), new Insets(0,0,0,0));
                            if (child instanceof JButton button && button.getText() != null && !button.getText().isEmpty())
                                assertFullText(button, button.getText(), button.getIcon(), button.getIconTextGap(), new Insets(0,0,0,0));
                        }
                    }
                }
            }
        });
    }

    @Test void formRowsFollowChangedContentWithoutShrinkingOrHorizontalOverflow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel column = TableDialogLayout.column();
            JLabel label = new JLabel("Short"); TableDialogLayout.stack(column, label);
            column.setSize(500, 100); layout(column);
            label.setText("WARMUP_ORDERS"); label.setFont(label.getFont().deriveFont(24f));
            layout(column);
            assertEquals(500, label.getWidth()); assertEquals(label.getPreferredSize().height, label.getHeight());
            assertEquals(label.getPreferredSize(), column.getPreferredSize());
            label.setVisible(false); assertEquals(0, column.getPreferredSize().height);
        });
    }

    private static void assertFullText(JComponent component, String text, Icon icon, int gap, Insets margin) {
        Insets insets = component.getInsets();
        Rectangle available = new Rectangle(0, 0, component.getWidth() - insets.left - insets.right - margin.left - margin.right,
                component.getHeight() - insets.top - insets.bottom);
        String rendered = SwingUtilities.layoutCompoundLabel(component, component.getFontMetrics(component.getFont()), text, icon,
                SwingConstants.CENTER, SwingConstants.LEFT, SwingConstants.CENTER, SwingConstants.RIGHT,
                available, new Rectangle(), new Rectangle(), gap);
        assertEquals(text, rendered, () -> "Clipped " + text + " in " + component.getSize());
    }
    private static boolean visible(Component component, Container root) {
        for (Component c = component; c != root; c = c.getParent()) if (c == null || !c.isVisible()) return false;
        return true;
    }
    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child); if (child instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }
}
