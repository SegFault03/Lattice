package com.segfault03.ideadb.dialog;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;

/** Rounded text-input outline with theme, focus, validation and disabled colors. */
final class RoundedInputBorder extends AbstractBorder {
    @Override
    public Insets getBorderInsets(Component component) {
        return JBUI.insets(5, 8);
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
        Insets padding = getBorderInsets(component);
        insets.set(padding.top, padding.left, padding.bottom, padding.right);
        return insets;
    }

    @Override
    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float arc = JBUI.scale(8);
            RoundRectangle2D outline = new RoundRectangle2D.Float(x + 1, y + 1, width - 3, height - 3, arc, arc);
            // Text UIs may paint a rectangular background; restore the parent's color at the corners.
            Area corners = new Area(new Rectangle(x, y, width, height));
            corners.subtract(new Area(outline));
            g.setColor(component.getParent() == null ? component.getBackground() : component.getParent().getBackground());
            g.fill(corners);
            boolean error = component instanceof JComponent input && "error".equals(input.getClientProperty("JComponent.outline"));
            Color color = error ? JBColor.namedColor("Component.errorFocusColor", JBColor.RED)
                    : component.hasFocus() ? JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7))
                    : JBColor.namedColor("Component.borderColor", new JBColor(0xB8B8B8, 0x64666B));
            g.setColor(component.isEnabled() ? color : JBColor.namedColor("Component.disabledBorderColor", JBColor.GRAY));
            g.setStroke(new BasicStroke(JBUI.scale(component.hasFocus() ? 2f : 1f)));
            g.draw(outline);
        } finally {
            g.dispose();
        }
    }
}
