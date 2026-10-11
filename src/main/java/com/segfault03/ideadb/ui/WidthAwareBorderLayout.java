package com.segfault03.ideadb.ui;

import java.awt.*;

/** Supplies the current available width before measuring wrapping header/footer rows. */
public final class WidthAwareBorderLayout extends BorderLayout {
    public WidthAwareBorderLayout() { super(); }
    public WidthAwareBorderLayout(int hgap, int vgap) { super(hgap, vgap); }

    @Override public Dimension preferredLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            prepareWidths(target);
            return super.preferredLayoutSize(target);
        }
    }

    @Override public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            prepareWidths(target);
            super.layoutContainer(target);
        }
    }

    private void prepareWidths(Container target) {
        if (target.getWidth() <= 0) return;
        Insets insets = target.getInsets();
        int width = Math.max(1, target.getWidth() - insets.left - insets.right);
        sizeWidth(getLayoutComponent(target, NORTH), width);
        sizeWidth(getLayoutComponent(target, SOUTH), width);
        Component west = getLayoutComponent(target, WEST);
        Component east = getLayoutComponent(target, EAST);
        if (west != null && west.isVisible()) width -= west.getPreferredSize().width + getHgap();
        if (east != null && east.isVisible()) width -= east.getPreferredSize().width + getHgap();
        sizeWidth(getLayoutComponent(target, CENTER), Math.max(1, width));
    }

    private static void sizeWidth(Component component, int width) {
        if (component != null && component.isVisible()) component.setSize(width, component.getHeight());
    }
}
