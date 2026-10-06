package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerToolbarTest {
    @Test
    void emptyExplorerDisablesConnectionActionsAndRestoresThemAfterAddingAConnection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            Runnable action = calls::incrementAndGet;
            DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(
                    component -> action.run(), action, action, action, action, action);
            List<JButton> buttons = new ArrayList<>();
            for (Component child : toolbar.getComponents()) {
                if (child instanceof JButton button) buttons.add(button);
            }
            assertEquals(6, buttons.size());
            for (int i = 1; i <= 4; i++) {
                assertTrue(buttons.get(i).isVisible());
                assertFalse(buttons.get(i).isEnabled());
                buttons.get(i).doClick(0);
            }
            assertEquals(0, calls.get(), "Disabled actions must not invoke their handlers");
            assertTrue(buttons.get(0).isEnabled());
            assertTrue(buttons.get(5).isEnabled());
            toolbar.setHasConnections(true);
            for (int i = 1; i <= 4; i++) {
                assertTrue(buttons.get(i).isEnabled());
                buttons.get(i).doClick(0);
            }
            assertEquals(4, calls.get());
            toolbar.setHasConnections(false);
            assertFalse(buttons.get(1).isEnabled());
            toolbar.setSize(toolbar.getPreferredSize());
            toolbar.doLayout();
            assertTrue(toolbar.getWidth() < 260);
            for (JButton button : buttons) {
                assertEquals(button.getWidth(), button.getHeight());
                assertTrue(button.getWidth() <= 30);
            }
        });
    }
}
