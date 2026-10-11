package com.segfault03.ideadb.ui;

import java.awt.*;

/** FlowLayout that includes wrapped rows in its preferred height. */
public final class WrapLayout extends FlowLayout {
    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true, target.getWidth());
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return layoutSize(target, false, target.getWidth());
    }

    private Dimension layoutSize(Container target, boolean preferred, int targetWidth) {
        synchronized (target.getTreeLock()) {
            if (targetWidth <= 0) targetWidth = Integer.MAX_VALUE;
            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int maxWidth = targetWidth - (insets.left + insets.right + hgap * 2);
            maxWidth = Math.max(1, maxWidth);

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            int count = target.getComponentCount();

            for (int i = 0; i < count; i++) {
                Component m = target.getComponent(i);
                if (m.isVisible()) {
                    Dimension d = componentSize(m, preferred, maxWidth);
                    if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                        dim.width = Math.max(dim.width, rowWidth);
                        dim.height += rowHeight + vgap;
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth > 0) rowWidth += hgap;
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
            }
            dim.width = Math.max(dim.width, rowWidth) + insets.left + insets.right + hgap * 2;
            dim.height += rowHeight + insets.top + insets.bottom + vgap * 2;
            return dim;
        }
    }

    private Dimension componentSize(Component component, boolean preferred, int availableWidth) {
        if (component instanceof WrappingLabel label) return label.preferredSizeForWidth(availableWidth);
        if (component instanceof Container container && container.getLayout() instanceof WrapLayout wrap)
            return wrap.layoutSize(container, preferred, availableWidth);
        Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
        return new Dimension(Math.min(size.width, availableWidth), size.height);
    }

    @Override public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int width = Math.max(1, target.getWidth() - insets.left - insets.right - 2 * getHgap());
            int y = insets.top + getVgap(), rowWidth = 0, rowHeight = 0, start = 0;
            Component[] children = target.getComponents();
            for (int i = 0; i < children.length; i++) {
                Component child = children[i];
                if (!child.isVisible()) continue;
                Dimension size = componentSize(child, true, width);
                child.setSize(size);
                if (rowWidth > 0 && rowWidth + getHgap() + size.width > width) {
                    placeRow(target, children, start, i, insets.left + getHgap(), y, width, rowWidth, rowHeight);
                    y += rowHeight + getVgap();
                    start = i;
                    rowWidth = rowHeight = 0;
                }
                if (rowWidth > 0) rowWidth += getHgap();
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            placeRow(target, children, start, children.length, insets.left + getHgap(), y, width, rowWidth, rowHeight);
        }
    }

    private void placeRow(Container target, Component[] children, int start, int end, int x, int y,
                          int width, int rowWidth, int rowHeight) {
        boolean leftToRight = target.getComponentOrientation().isLeftToRight();
        int align = getAlignment();
        if (align == CENTER) x += (width - rowWidth) / 2;
        else if (align == LEFT && !leftToRight || align == RIGHT && leftToRight || align == TRAILING)
            x += width - rowWidth;
        for (int i = start; i < end; i++) {
            Component child = children[i];
            if (!child.isVisible()) continue;
            child.setLocation(leftToRight ? x : target.getWidth() - x - child.getWidth(),
                    y + (rowHeight - child.getHeight()) / 2);
            x += child.getWidth() + getHgap();
        }
    }
}
