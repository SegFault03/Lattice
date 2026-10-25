package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** A drag rail with keyboard resizing, independent of the content's layout. */
final class ResizeHandle extends JPanel {
    private final boolean horizontal;
    private boolean hovered, dragging;

    ResizeHandle(String name, boolean horizontal, int direction, IntSupplier size, IntConsumer resize) {
        this.horizontal = horizontal;
        setOpaque(false); setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(horizontal ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
        setToolTipText(name + "; drag or use arrow keys");
        getAccessibleContext().setAccessibleName(name);
        DatabaseUi.installFocusVisibility(this);
        MouseAdapter mouse = new MouseAdapter() {
            private Component coordinates;
            private int origin, originalSize;
            private int position(MouseEvent event) {
                Point point = SwingUtilities.convertPoint(ResizeHandle.this, event.getPoint(), coordinates);
                return horizontal ? point.x : point.y;
            }
            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                coordinates = ResizeHandle.this;
                while (coordinates.getParent() != null) coordinates = coordinates.getParent();
                origin = position(event); originalSize = size.getAsInt();
                dragging = true; requestFocusInWindow(); repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                if (dragging) resize.accept(originalSize + direction * (position(event) - origin));
            }
            @Override public void mouseReleased(MouseEvent event) { dragging = false; repaint(); }
            @Override public void mouseEntered(MouseEvent event) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent event) { hovered = false; repaint(); }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse);
        String decrease = horizontal ? "LEFT" : "UP", increase = horizontal ? "RIGHT" : "DOWN";
        for (String key : new String[]{decrease, increase}) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
            getActionMap().put(key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    resize.accept(size.getAsInt() + (key.equals(increase) ? direction : -direction) * JBUI.scale(16));
                }
            });
        }
    }

    @Override public Dimension getPreferredSize() {
        return horizontal ? new Dimension(JBUI.scale(6), JBUI.scale(32))
                : new Dimension(JBUI.scale(32), JBUI.scale(8));
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean active = hovered || dragging || isFocusOwner() && DatabaseUi.isFocusVisible(this);
        g.setColor(active ? new JBColor(0x3574F0, 0x78A9FF) : new JBColor(0xA4ADBB, 0x626B79));
        if (horizontal) {
            g.fillRoundRect((getWidth() - JBUI.scale(2)) / 2, (getHeight() - JBUI.scale(24)) / 2,
                    JBUI.scale(2), JBUI.scale(24), JBUI.scale(2), JBUI.scale(2));
        } else {
            g.fillRoundRect((getWidth() - JBUI.scale(24)) / 2, (getHeight() - JBUI.scale(2)) / 2,
                    JBUI.scale(24), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2));
        }
        g.dispose();
    }
}
