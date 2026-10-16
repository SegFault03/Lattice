package com.segfault03.ideadb.dialog;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/** Full-width form rows whose heights follow their current font and content. */
final class TableDialogLayout {
    private TableDialogLayout() {}

    static JPanel column() { return new Column(); }

    static void stack(JPanel column, JComponent row) { column.add(row); }

    static Dimension preferredSize(JPanel panel, int width, int minimumHeight, int maximumHeight) {
        int contentHeight = panel.getLayout().preferredLayoutSize(panel).height;
        return new Dimension(JBUI.scale(width), Math.max(JBUI.scale(minimumHeight), Math.min(JBUI.scale(maximumHeight), contentHeight)));
    }

    static JPanel width(JComponent field, int width) {
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                return new Dimension(Math.max(JBUI.scale(width), field.getMinimumSize().width), field.getPreferredSize().height);
            }
        };
        wrapper.setOpaque(false); wrapper.add(field, BorderLayout.CENTER); return wrapper;
    }

    private static final class Column extends JPanel implements Scrollable {
        Column() { super(new Rows()); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(20); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(JBUI.scale(20), visible.height - JBUI.scale(20));
        }
    }

    /** Recompute sizes on each pass; never cache a label's early, pre-realization width. */
    private static final class Rows implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }
        @Override public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = 0, height = insets.top + insets.bottom;
            for (Component row : parent.getComponents()) if (row.isVisible()) {
                Dimension size = row.getPreferredSize();
                width = Math.max(width, size.width); height += size.height;
            }
            return new Dimension(width + insets.left + insets.right, height);
        }
        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int y = insets.top, width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            for (Component row : parent.getComponents()) if (row.isVisible()) {
                int height = row.getPreferredSize().height;
                row.setBounds(insets.left, y, width, height); y += height;
            }
        }
    }
}
