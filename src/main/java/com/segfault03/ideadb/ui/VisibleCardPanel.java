package com.segfault03.ideadb.ui;

import javax.swing.*;
import java.awt.*;

/** Card container whose height follows its visible content, instead of its largest card. */
public final class VisibleCardPanel extends JPanel {
    public VisibleCardPanel(CardLayout layout) { super(layout); }

    @Override public Dimension getPreferredSize() { return visibleSize(false); }
    @Override public Dimension getMinimumSize() { return visibleSize(true); }

    private Dimension visibleSize(boolean minimum) {
        Dimension size = new Dimension();
        for (Component child : getComponents()) {
            if (child.isVisible()) {
                size = new Dimension(minimum ? child.getMinimumSize() : child.getPreferredSize());
                break;
            }
        }
        Insets insets = getInsets();
        CardLayout layout = (CardLayout) getLayout();
        size.width += insets.left + insets.right + 2 * layout.getHgap();
        size.height += insets.top + insets.bottom + 2 * layout.getVgap();
        return size;
    }
}
