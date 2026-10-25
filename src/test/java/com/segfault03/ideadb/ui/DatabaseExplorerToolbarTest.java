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
                    component -> action.run(), action, action, action, action, component -> action.run(), action, action);
            assertInstanceOf(javax.swing.border.EmptyBorder.class, toolbar.getBorder(),
                    "The explorer must leave the section boundary to the IDE tool-window header");
            List<JButton> buttons = new ArrayList<>();
            for (Component child : toolbar.getComponents()) {
                if (child instanceof JButton button) buttons.add(button);
            }
            assertEquals(8, buttons.size());
            for (int i = 1; i <= 5; i++) {
                assertTrue(buttons.get(i).isVisible());
                assertFalse(buttons.get(i).isEnabled());
                buttons.get(i).doClick(0);
            }
            assertEquals(0, calls.get(), "Disabled actions must not invoke their handlers");
            assertTrue(buttons.get(0).isEnabled());
            assertTrue(buttons.get(6).isEnabled());
            assertTrue(buttons.get(7).isEnabled());
            toolbar.setHasConnections(true);
            ConnectionConfig config = new ConnectionConfig();
            toolbar.setSelection(TreeNodeData.connection(config, false));
            for (int i = 1; i <= 5; i++) {
                assertEquals(i != 4, buttons.get(i).isEnabled(), "A disconnected source cannot open a SQL console");
                buttons.get(i).doClick(0);
            }
            assertEquals(4, calls.get());
            toolbar.setSelection(TreeNodeData.connection(config, true));
            assertTrue(buttons.get(4).isEnabled());
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
            DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, component -> {}, noop, noop);
            List<JButton> buttons = buttons(toolbar);
            toolbar.setHasConnections(true);
            assertFalse(buttons.get(1).isEnabled());
            assertFalse(buttons.get(2).isEnabled());
            assertTrue(buttons.get(3).isEnabled());
            assertFalse(buttons.get(4).isEnabled(), "Saved sources alone do not make a console available");

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
            for (int i = 1; i <= 5; i++) assertFalse(buttons.get(i).isEnabled());
        });
    }

    @Test void disconnectedSelectionsDimTheConsoleInTheToolbarAndItsOverflowAndReconnectRestoresIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable noop = () -> {};
            var toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, component -> {}, noop, noop);
            toolbar.setHasConnections(true);
            var buttons = buttons(toolbar);
            var console = buttons.get(4);
            var overflow = DatabaseUi.mirrorAction(console);
            overflow.addNotify();
            try {
                var config = new ConnectionConfig();
                var cached = TreeNodeData.table(config, "shop", new TableMetadata("shop", null, "products", "TABLE"));
                cached.setCached(true);
                toolbar.setSelection(cached, false);
                assertFalse(console.isEnabled());
                assertFalse(overflow.isEnabled());
                assertTrue(console.getToolTipText().contains("Connect"));
                assertTrue(buttons.get(0).isEnabled(), "Adding a connection works offline");
                assertTrue(buttons.get(1).isEnabled(), "Connection settings work offline");
                assertTrue(buttons.get(3).isEnabled(), "Refresh remains available to reconnect");
                assertTrue(buttons.get(5).isEnabled(), "Source filtering works offline");
                assertTrue(buttons.get(6).isEnabled(), "Search includes cached metadata");
                assertTrue(buttons.get(7).isEnabled());
                var presentation = new com.intellij.openapi.actionSystem.Presentation();
                var action = new DatabaseActionToolbar.ControlAction(console, null);
                action.updatePresentation(presentation);
                assertFalse(presentation.isEnabled());
                assertTrue(presentation.getDescription().contains("Connect"));
                assertNotNull(presentation.getDisabledIcon());
                cached.setCached(false);
                toolbar.setSelection(cached, true);
                action.updatePresentation(presentation);
                assertTrue(console.isEnabled()); assertTrue(overflow.isEnabled()); assertTrue(presentation.isEnabled());
                assertEquals("Open SQL Console", presentation.getDescription());
                toolbar.setBusy(true);
                assertFalse(console.isEnabled()); assertFalse(overflow.isEnabled());
                toolbar.setBusy(false);
                toolbar.setSelection(cached, false);
                assertFalse(console.isEnabled()); assertFalse(overflow.isEnabled());
            } finally { overflow.removeNotify(); }
        });
    }

    @Test void filterIndicatorAndOverflowStayActiveUntilAllSourcesAreRestored() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable noop = () -> {};
            DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, component -> {}, noop, noop);
            toolbar.setHasConnections(true);
            JButton eye = buttons(toolbar).stream()
                    .filter(button -> "View only…".equals(button.getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow();
            JButton overflow = DatabaseUi.mirrorAction(eye);
            overflow.addNotify();
            try {
                toolbar.setSourceFilter("MySQL", true);
                assertTrue(eye.isSelected());
                assertTrue(overflow.isSelected());
                assertEquals("View only: MySQL", overflow.getToolTipText());
                toolbar.setBusy(true);
                assertFalse(eye.isEnabled());
                assertFalse(overflow.isEnabled());
                toolbar.setBusy(false);
                assertTrue(eye.isEnabled());
                assertTrue(overflow.isSelected(), "Finishing work preserves the applied filter indicator");
                toolbar.setSourceFilter("All data sources", false);
                assertFalse(eye.isSelected());
                assertFalse(overflow.isSelected());
                assertEquals("View only: All data sources", overflow.getToolTipText());
            } finally { overflow.removeNotify(); }
        });
    }

    private static List<JButton> buttons(DatabaseExplorerToolbar toolbar) {
        List<JButton> result = new ArrayList<>();
        for (Component child : toolbar.getComponents()) if (child instanceof JButton button) result.add(button);
        return result;
    }

    private static int pixel(JButton button) {
        return pixel(button, JBUI.scale(3), JBUI.scale(3));
    }

    private static int pixel(JButton button, int x, int y) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { button.paint(graphics); }
        finally { graphics.dispose(); }
        return image.getRGB(x, y);
    }
}
