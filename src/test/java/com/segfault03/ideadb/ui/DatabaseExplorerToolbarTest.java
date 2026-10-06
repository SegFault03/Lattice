package com.segfault03.ideadb.ui;

import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
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
            ConnectionConfig config = new ConnectionConfig();
            toolbar.setSelection(TreeNodeData.connection(config, false));
            for (int i = 1; i <= 4; i++) {
                assertTrue(buttons.get(i).isEnabled());
                buttons.get(i).doClick(0);
            }
            assertEquals(4, calls.get());
            toolbar.setHasConnections(false);
            assertFalse(buttons.get(1).isEnabled());
            for (int width : new int[]{260, 420, 600}) {
                toolbar.setSize(JBUI.scale(width), toolbar.getPreferredSize().height);
                toolbar.doLayout();
                for (JButton button : buttons) {
                    assertEquals(JBUI.scale(28), button.getWidth(), "Actions keep their size when the pane grows");
                    assertEquals(button.getWidth(), button.getHeight());
                    assertFalse(button.isBorderPainted());
                    assertNotNull(button.getDisabledIcon());
                }
                assertTrue(buttons.get(0).getX() >= JBUI.scale(12));
                assertTrue(buttons.get(0).getY() >= JBUI.scale(6));
            }
        });
    }

    @Test
    void actionAvailabilityFollowsTheSelectedNodeAndClearsAfterRemovingConnections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable noop = () -> {};
            DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, noop);
            List<JButton> buttons = buttons(toolbar);
            toolbar.setHasConnections(true);
            assertFalse(buttons.get(1).isEnabled());
            assertFalse(buttons.get(2).isEnabled());
            assertTrue(buttons.get(3).isEnabled());
            assertTrue(buttons.get(4).isEnabled());

            ConnectionConfig config = new ConnectionConfig();
            toolbar.setSelection(TreeNodeData.connection(config, false));
            assertTrue(buttons.get(1).isEnabled());
            assertTrue(buttons.get(2).isEnabled());
            toolbar.setSelection(TreeNodeData.table(config, "shop", new TableMetadata("shop", null, "products", "TABLE")));
            assertTrue(buttons.get(1).isEnabled());
            assertFalse(buttons.get(2).isEnabled(), "Remove Connection is unavailable on table nodes");
            assertTrue(buttons.get(3).isEnabled());
            toolbar.setSelection(TreeNodeData.tablesFolder(config, "shop", 1));
            assertFalse(buttons.get(3).isEnabled(), "Refresh must not look available when its handler would do nothing");
            toolbar.setSelection(TreeNodeData.root());
            assertFalse(buttons.get(1).isEnabled());
            assertTrue(buttons.get(3).isEnabled());
            toolbar.setHasConnections(false);
            for (int i = 1; i <= 4; i++) assertFalse(buttons.get(i).isEnabled());
        });
    }

    @Test
    void hoverAndPressHaveDistinctBackgroundsAndDisabledButtonsDoNotHighlight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable noop = () -> {};
            DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, noop);
            JButton button = buttons(toolbar).get(3);
            toolbar.setHasConnections(true);
            button.setSize(button.getPreferredSize());
            int normal = pixel(button);
            button.getModel().setRollover(true);
            int hover = pixel(button);
            assertNotEquals(normal, hover);
            button.getModel().setArmed(true);
            button.getModel().setPressed(true);
            int pressed = pixel(button);
            assertNotEquals(normal, pressed);
            assertNotEquals(hover, pressed);
            button.getModel().setPressed(false);
            button.getModel().setArmed(false);
            button.setEnabled(false);
            button.getModel().setRollover(true);
            assertEquals(normal, pixel(button), "Unavailable actions have no hover or pressed background");
        });
    }

    private static List<JButton> buttons(DatabaseExplorerToolbar toolbar) {
        List<JButton> result = new ArrayList<>();
        for (Component child : toolbar.getComponents()) if (child instanceof JButton button) result.add(button);
        return result;
    }

    private static int pixel(JButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { button.paint(graphics); }
        finally { graphics.dispose(); }
        return image.getRGB(JBUI.scale(3), JBUI.scale(3));
    }
}
