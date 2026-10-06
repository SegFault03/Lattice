package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmptyConnectionPanelTest {
    @Test
    void promptStaysCenteredAndLinkOpensTheConnectionDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger opened = new AtomicInteger();
            EmptyConnectionPanel panel = new EmptyConnectionPanel(opened::incrementAndGet);
            for (int width : new int[]{260, 400}) {
                panel.setSize(width, 400);
                panel.doLayout();
                for (Component child : panel.getComponents()) {
                    assertEquals(width / 2.0, child.getX() + child.getWidth() / 2.0, 1.0);
                    assertTrue(child.getY() > 140 && child.getY() < 240);
                    if (child instanceof JButton link) {
                        assertTrue(link.isEnabled());
                        link.doClick(0);
                    }
                }
            }
            assertEquals(2, opened.get());
        });
    }
}
