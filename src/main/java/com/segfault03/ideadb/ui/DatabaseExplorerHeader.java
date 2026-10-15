package com.segfault03.ideadb.ui;

import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Places the search drawer beside the actions only when both fit at their natural size. */
final class DatabaseExplorerHeader extends JPanel {
    private final JComponent toolbar;
    private final DatabaseExplorerSearchBar search;

    DatabaseExplorerHeader(JComponent toolbar, DatabaseExplorerSearchBar search) {
        super(null);
        this.toolbar = toolbar;
        this.search = search;
        add(toolbar);
        add(search);
        search.setVisible(false);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { revalidate(); }
        });
    }

    private int availableWidth() { return getWidth() > 0 ? getWidth() : getParent() == null ? 0 : getParent().getWidth(); }
    private int actionWidth() { return Math.max(JBUI.scale(280), toolbar.getPreferredSize().width); }
    private boolean inline(int width) { return width >= actionWidth() + search.getPreferredSize().width; }

    @Override public Dimension getPreferredSize() {
        int width = availableWidth(), actionHeight = toolbar.getPreferredSize().height;
        int height = !search.isVisible() ? actionHeight : inline(width)
                ? Math.max(actionHeight, search.heightForWidth(width - actionWidth()))
                : actionHeight + search.heightForWidth(width);
        return new Dimension(actionWidth() + (search.isVisible() ? search.getPreferredSize().width : 0), height);
    }

    @Override public void doLayout() {
        int width = getWidth(), actionHeight = toolbar.getPreferredSize().height;
        boolean inline = search.isVisible() && inline(width);
        toolbar.setBounds(0, 0, inline ? actionWidth() : width, actionHeight);
        if (search.isVisible()) {
            int searchWidth = inline ? width - actionWidth() : width;
            search.setBounds(inline ? actionWidth() : 0, inline ? 0 : actionHeight, searchWidth, search.heightForWidth(searchWidth));
        }
    }
}
