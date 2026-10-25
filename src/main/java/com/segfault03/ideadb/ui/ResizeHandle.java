package com.segfault03.ideadb.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** An invisible resize hit area with a directional cursor and keyboard support. */
final class ResizeHandle extends JPanel {
    private final boolean horizontal;
    private boolean dragging;

    ResizeHandle(String name, boolean horizontal, int direction, IntSupplier size, IntConsumer resize) {
        this.horizontal = horizontal;
        setOpaque(false); setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(horizontal ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
        getAccessibleContext().setAccessibleName(name);
        getAccessibleContext().setAccessibleDescription("Drag or use arrow keys to resize");
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

}
