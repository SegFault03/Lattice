package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResizeHandleTest {
    @Test void dragAndKeyboardResizeAlongTheRequestedAxisAndDirection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean horizontal : new boolean[]{true, false}) {
                AtomicInteger size = new AtomicInteger(200);
                ResizeHandle handle = new ResizeHandle("Resize", horizontal, horizontal ? -1 : 1,
                        size::get, size::set);
                JPanel parent = new JPanel(null); parent.setSize(500, 500);
                parent.add(handle); handle.setBounds(20, 20, 100, 20);
                handle.dispatchEvent(new MouseEvent(handle, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
                handle.dispatchEvent(new MouseEvent(handle, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK,
                        horizontal ? 35 : 5, horizontal ? 5 : 35, 0, false, MouseEvent.NOBUTTON));
                handle.dispatchEvent(new MouseEvent(handle, MouseEvent.MOUSE_RELEASED, 0, 0, 35, 35, 1, false, MouseEvent.BUTTON1));
                assertEquals(horizontal ? 170 : 230, size.get());
                handle.getActionMap().get(horizontal ? "LEFT" : "UP").actionPerformed(null);
                assertEquals(horizontal ? 186 : 214, size.get());
            }
        });
    }
}
