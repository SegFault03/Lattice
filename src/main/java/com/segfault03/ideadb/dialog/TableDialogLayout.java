package com.segfault03.ideadb.dialog;

import com.intellij.util.ui.JBUI;
import com.intellij.ui.components.JBScrollPane;
import com.segfault03.ideadb.ui.WrapLayout;
import com.segfault03.ideadb.ui.WrappingLabel;

import javax.swing.*;
import java.awt.*;

/** Full-width form rows whose heights follow their current font and content. */
final class TableDialogLayout {
    private TableDialogLayout() {}

    static JPanel column() { return new Column(); }

    static void stack(JPanel column, JComponent row) { column.add(row); }

    interface WidthAware { Dimension preferredSizeForWidth(int width); }

    static JScrollPane inspector(JComponent content) {
        javax.swing.border.Border spacing = content.getBorder();
        JBScrollPane scroll = new JBScrollPane(content);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setViewportBorder(JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // Reserve the native scrollbar's width in our spacing from the start. Otherwise
        // JBViewport adds it to the form's border only while a transparent bar is visible,
        // wrapping hints and making that bar keep itself visible after a resize.
        scroll.setOverlappingScrollBar(true);
        content.setBorder(new javax.swing.border.AbstractBorder() {
            @Override public Insets getBorderInsets(Component component) {
                Insets base = spacing == null ? new Insets(0,0,0,0) : spacing.getBorderInsets(component);
                int right = Math.max(base.right, scroll.getVerticalScrollBar().getPreferredSize().width + JBUI.scale(4));
                return new Insets(base.top, base.left, base.bottom, right);
            }
            @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
                if (spacing != null) spacing.paintBorder(component,graphics,x,y,width,height);
            }
        });
        scroll.getAccessibleContext().setAccessibleName("Table column inspector scroll pane");
        return scroll;
    }

    /** The SQL viewport absorbs spare space and can shrink to three readable text lines. */
    static JComponent preview(JPanel surface, JComponent caption, JTextPane sql, int preferredHeight) {
        return new Preview(surface, caption, sql, preferredHeight);
    }

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

    private static final class Column extends JPanel implements Scrollable, WidthAware {
        Column() { super(new Rows()); setOpaque(false); }
        @Override public Dimension preferredSizeForWidth(int width) { return ((Rows)getLayout()).measure(this, width, false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            if (!(getParent() instanceof JViewport viewport)) return false;
            int width = viewport.getWidth(), height = viewport.getHeight();
            if (viewport.getParent() instanceof JScrollPane scroll) {
                // Negotiate without the old vertical bar: its narrower viewport can wrap a
                // hint and keep the bar visible even after the dialog has enough room again.
                Insets outer=scroll.getInsets(), border=scroll.getViewportBorder()==null?new Insets(0,0,0,0):scroll.getViewportBorder().getBorderInsets(scroll);
                int rowHeader=scroll.getRowHeader()!=null && scroll.getRowHeader().isVisible()?scroll.getRowHeader().getWidth():0;
                width=Math.max(0,scroll.getWidth()-outer.left-outer.right-border.left-border.right-rowHeader);
                if (scroll.getHorizontalScrollBarPolicy()==ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                    int columnHeader=scroll.getColumnHeader()!=null && scroll.getColumnHeader().isVisible()?scroll.getColumnHeader().getHeight():0;
                    height=Math.max(0,scroll.getHeight()-outer.top-outer.bottom-border.top-border.bottom-columnHeader);
                }
            }
            return height >= ((Rows)getLayout()).measure(this, width, true).height;
        }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(20); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(JBUI.scale(20), visible.height - JBUI.scale(20));
        }
    }

    private static final class Preview extends JPanel {
        private final JPanel surface;
        private final JComponent caption;
        private final JTextPane sql;
        private final int preferredHeight;
        Preview(JPanel surface, JComponent caption, JTextPane sql, int preferredHeight) {
            super(new BorderLayout()); setOpaque(false); add(surface, BorderLayout.CENTER);
            this.surface=surface;this.caption=caption;this.sql=sql;this.preferredHeight=preferredHeight;
        }
        @Override public Dimension getMinimumSize() {
            Insets padding = surface.getInsets(), text = sql.getInsets();
            int gap = ((BorderLayout)surface.getLayout()).getVgap();
            int lines = sql.getFontMetrics(sql.getFont()).getHeight() * 3;
            return new Dimension(0, padding.top + padding.bottom + caption.getPreferredSize().height + gap + lines + text.top + text.bottom);
        }
        @Override public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(400), Math.max(JBUI.scale(preferredHeight), getMinimumSize().height));
        }
    }

    /** Recompute sizes on each pass; never cache a label's early, pre-realization width. */
    private static final class Rows implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension minimumLayoutSize(Container parent) { return measure(parent, parent.getWidth(), true); }
        @Override public Dimension preferredLayoutSize(Container parent) { return measure(parent, parent.getWidth(), false); }
        private Dimension rowSize(Component row, int width, boolean minimum) {
            if (minimum && row instanceof Preview) return row.getMinimumSize();
            if (row instanceof WidthAware responsive) return responsive.preferredSizeForWidth(width);
            if (row instanceof WrappingLabel label) return label.preferredSizeForWidth(width);
            if (row instanceof Container container && container.getLayout() instanceof WrapLayout wrap)
                return wrap.preferredSizeForWidth(container, width);
            return row.getPreferredSize();
        }
        Dimension measure(Container parent, int allocatedWidth, boolean minimum) {
            Insets insets = parent.getInsets();
            int available = allocatedWidth <= 0 ? 0 : Math.max(1, allocatedWidth-insets.left-insets.right);
            int width = 0, height = insets.top + insets.bottom;
            for (Component row : parent.getComponents()) if (row.isVisible()) {
                Dimension size = rowSize(row, available, minimum);
                width = Math.max(width, size.width); height += size.height;
            }
            return new Dimension(width + insets.left + insets.right, height);
        }
        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int y = insets.top, width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            int fixedHeight = 0, flexible = 0, minimumHeight = 0;
            for (Component row : parent.getComponents()) if (row.isVisible()) {
                if (row instanceof Preview) { flexible++; minimumHeight += row.getMinimumSize().height; }
                else fixedHeight += rowSize(row, width, false).height;
            }
            int spare = Math.max(0, parent.getHeight()-insets.top-insets.bottom-fixedHeight-minimumHeight);
            for (Component row : parent.getComponents()) if (row.isVisible()) {
                int height = row instanceof Preview ? row.getMinimumSize().height + spare / Math.max(1, flexible)
                        : rowSize(row, width, false).height;
                row.setBounds(insets.left, y, width, height); y += height;
            }
        }
    }
}
