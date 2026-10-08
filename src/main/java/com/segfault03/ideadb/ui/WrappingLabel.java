package com.segfault03.ideadb.ui;

import com.intellij.ui.components.JBLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.*;

/** A native Swing label whose full status text wraps at its allocated width. */
public final class WrappingLabel extends JBLabel {
    public WrappingLabel(String text) {
        super(text);
        setVerticalAlignment(TOP);
    }

    @Override public void setText(String text) {
        super.setText(text);
        setToolTipText(text);
    }

    @Override public Dimension getPreferredSize() {
        Container parent = getParent();
        int width = parent == null ? getWidth()
                : parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
        return preferredSizeForWidth(width);
    }

    Dimension preferredSizeForWidth(int width) {
        prepareView(width);
        Dimension size = super.getPreferredSize();
        if (width > 0) size.width = Math.min(size.width, width);
        return size;
    }

    @Override public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        prepareView(width);
    }

    private void prepareView(int width) {
        if (getText() == null || getText().isEmpty()) return;
        View view = (View) getClientProperty(BasicHTML.propertyKey);
        if (view == null) {
            String text = getText().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br>");
            BasicHTML.updateRenderer(this, "<html>" + text + "</html>");
            view = (View) getClientProperty(BasicHTML.propertyKey);
        }
        if (view != null && width > 0) {
            Insets insets = getInsets();
            int iconWidth = getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap();
            view.setSize(Math.max(1, width - insets.left - insets.right - iconWidth), 0);
        }
    }
}
