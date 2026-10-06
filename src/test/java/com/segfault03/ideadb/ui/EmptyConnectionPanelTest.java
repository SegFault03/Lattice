package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
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

    @Test
    void mouseAndDialogFocusRestorationLeaveNoOutlineButKeyboardTraversalKeepsItsCue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmptyConnectionPanel panel = new EmptyConnectionPanel(() -> {});
            JButton link = (JButton) panel.getComponent(1);
            assertTrue(link.isFocusable(), "The link remains keyboard accessible");
            assertFalse(link.isFocusPainted());
            focus(link, FocusEvent.Cause.TRAVERSAL_FORWARD);
            assertTrue(link.isFocusPainted());
            link.dispatchEvent(new MouseEvent(link, MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
            assertFalse(link.isFocusPainted());
            focus(link, FocusEvent.Cause.ACTIVATION);
            assertFalse(link.isFocusPainted(), "Restoring focus after the dialog closes must not leave a border");
            focus(link, FocusEvent.Cause.UNKNOWN);
            assertFalse(link.isFocusPainted());
            focus(link, FocusEvent.Cause.TRAVERSAL_BACKWARD);
            assertTrue(link.isFocusPainted());
        });
    }

    private static void focus(JButton link, FocusEvent.Cause cause) {
        FocusEvent event = new FocusEvent(link, FocusEvent.FOCUS_GAINED, false, null, cause);
        for (FocusListener listener : link.getFocusListeners()) listener.focusGained(event);
    }
}
