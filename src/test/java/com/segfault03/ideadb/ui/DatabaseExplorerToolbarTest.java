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
                assertTrue(buttons.get(i).isEnabled());
                buttons.get(i).doClick(0);
            }
            assertEquals(5, calls.get());
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
            for (int i = 1; i <= 5; i++) assertFalse(buttons.get(i).isEnabled());
        });
    }

    @Test
    void hoverAndPressUseNativeThemeColorsWithoutRecoloringGlyphs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object previousHover = UIManager.get("ActionButton.hoverBackground");
            Object previousPressed = UIManager.get("ActionButton.pressedBackground");
            Color glyph = new Color(0x2878A0);
            try {
                for (Color[] palette : new Color[][]{
                        {new Color(0xD6D6D6), new Color(0xC0C0C0)},
                        {new Color(0x44464A), new Color(0x53565B)}}) {
                    UIManager.put("ActionButton.hoverBackground", palette[0]);
                    UIManager.put("ActionButton.pressedBackground", palette[1]);
                    Runnable noop = () -> {};
                    DatabaseExplorerToolbar toolbar = new DatabaseExplorerToolbar(component -> {}, noop, noop, noop, noop, component -> {}, noop, noop);
                    JButton button = buttons(toolbar).get(3);
                    button.setIcon(new Icon() {
                        public int getIconWidth() { return JBUI.scale(8); }
                        public int getIconHeight() { return JBUI.scale(8); }
                        public void paintIcon(Component component, Graphics graphics, int x, int y) {
                            graphics.setColor(glyph);
                            graphics.fillRect(x, y, getIconWidth(), getIconHeight());
                        }
                    });
                    toolbar.setHasConnections(true);
                    button.setSize(button.getPreferredSize());
                    int normal = pixel(button);
                    int centerX = button.getWidth() / 2, centerY = button.getHeight() / 2;
                    assertEquals(glyph.getRGB(), pixel(button, centerX, centerY));
                    button.getModel().setRollover(true);
                    assertEquals(palette[0].getRGB(), pixel(button), "Hover follows the IDE theme's action background");
                    assertEquals(glyph.getRGB(), pixel(button, centerX, centerY), "Hover keeps the icon's original colors");
                    button.getModel().setArmed(true);
                    button.getModel().setPressed(true);
                    assertEquals(palette[1].getRGB(), pixel(button));
                    assertEquals(glyph.getRGB(), pixel(button, centerX, centerY));
                    button.getModel().setPressed(false);
                    button.getModel().setArmed(false);
                    button.setEnabled(false);
                    button.getModel().setRollover(true);
                    assertEquals(normal, pixel(button), "Unavailable actions have no hover or pressed background");
                }
            } finally {
                UIManager.put("ActionButton.hoverBackground", previousHover);
                UIManager.put("ActionButton.pressedBackground", previousPressed);
            }
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
