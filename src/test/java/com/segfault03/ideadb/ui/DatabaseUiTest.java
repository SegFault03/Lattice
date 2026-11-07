package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUiTest {
    @Test void aSolidColorIconKeepsItsSizeAndAlphaAndReplacesEveryColor() {
        Icon source = new Icon() {
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                graphics.setColor(new Color(40, 90, 200, 128));
                graphics.fillRect(x, y, 2, 4);
            }
            @Override public int getIconWidth() { return 4; }
            @Override public int getIconHeight() { return 4; }
        };
        Icon white = DatabaseUi.solidColorIcon(source, new Color(255, 255, 255, 10));
        assertEquals(4, white.getIconWidth());
        assertEquals(4, white.getIconHeight());
        var target = new java.awt.image.BufferedImage(6, 6, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        white.paintIcon(null, graphics, 1, 1);
        graphics.dispose();
        Color painted = new Color(target.getRGB(1, 1), true);
        assertEquals(new Color(255, 255, 255).getRGB() & 0x00FFFFFF, painted.getRGB() & 0x00FFFFFF, "Gray and tinted glyphs must become the requested color");
        assertTrue(Math.abs(painted.getAlpha() - 128) <= 1, "The source alpha must survive: " + painted.getAlpha());
        assertEquals(0, new Color(target.getRGB(4, 1), true).getAlpha(), "Transparent pixels must stay transparent");
        assertEquals(0, new Color(target.getRGB(0, 0), true).getAlpha(), "The icon must paint at its position");
    }

    @Test void cachedPopupAndSubmenuRefreshTheirPaletteOnEveryOpen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] keys = {"PopupMenu.background", "MenuItem.background", "MenuItem.foreground"};
            Object[] previous = java.util.Arrays.stream(keys).map(UIManager::get).toArray();
            JPopupMenu menu = DatabaseUi.popupMenu();
            JMenu nested = DatabaseUi.submenu("More");
            try {
                JMenuItem count = new JMenuItem("Count rows"); menu.add(count);
                menu.add(nested);
                JMenuItem truncate = new JMenuItem("Truncate table"); nested.add(truncate);
                int listeners = menu.getPopupMenuListeners().length;
                for (Color background : new Color[]{Color.WHITE, new Color(0x25262A), Color.WHITE}) {
                    Color foreground = background.equals(Color.WHITE) ? Color.BLACK : Color.WHITE;
                    UIManager.put(keys[0], new javax.swing.plaf.ColorUIResource(background));
                    UIManager.put(keys[1], new javax.swing.plaf.ColorUIResource(background));
                    UIManager.put(keys[2], new javax.swing.plaf.ColorUIResource(foreground));
                    for (var listener : menu.getPopupMenuListeners())
                        listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                    JPopupMenu child = nested.getPopupMenu();
                    for (var listener : child.getPopupMenuListeners())
                        listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(child));
                    assertEquals(background, menu.getBackground()); assertEquals(background, child.getBackground());
                    assertEquals(background, count.getBackground()); assertEquals(foreground, count.getForeground());
                    assertEquals(background, truncate.getBackground()); assertEquals(foreground, truncate.getForeground());
                    assertTrue(menu.isOpaque()); assertTrue(child.isOpaque());
                    assertFalse(menu.isLightWeightPopupEnabled()); assertFalse(child.isLightWeightPopupEnabled());
                    assertEquals(listeners, menu.getPopupMenuListeners().length, "Opening must not accumulate listeners");
                }
            } finally {
                // Opening a JBPopupMenu starts its PopupTimer and closing stops it; leave no running timer behind.
                closePopup(nested.getPopupMenu());
                closePopup(menu);
                for (int index = 0; index < keys.length; index++) UIManager.put(keys[index], previous[index]);
            }
        });
    }

    @Test void openingAMenuWithAnUnchangedPaletteDoesNotReinstallItsUi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String key = "MenuItem.foreground";
            Object previous = UIManager.get(key);
            JPopupMenu menu = DatabaseUi.popupMenu();
            try {
                JMenuItem item = new JMenuItem("Count rows"); menu.add(item);
                var itemUi = item.getUI();
                var popupUi = menu.getUI();
                for (int open = 0; open < 3; open++)
                    for (var listener : menu.getPopupMenuListeners())
                        listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                assertSame(itemUi, item.getUI(), "A repeat open repaints over the first paint, so it must not reinstall the UI");
                assertSame(popupUi, menu.getUI());
                UIManager.put(key, new javax.swing.plaf.ColorUIResource(Color.MAGENTA));
                for (var listener : menu.getPopupMenuListeners())
                    listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                assertNotSame(itemUi, item.getUI(), "A palette change must still refresh the cached menu");
            } finally {
                closePopup(menu);
                UIManager.put(key, previous);
            }
        });
    }

    @Test void thePopupIsTheOpaqueSurfaceSoATranslucentHoverIsDrawnOverTheThemeBackground() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPopupMenu menu = DatabaseUi.popupMenu();
            JMenu nested = DatabaseUi.submenu("Export");
            try {
                JMenuItem count = new JMenuItem("Count rows"); count.setOpaque(true); menu.add(count);
                menu.addSeparator(); menu.add(nested);
                JMenuItem csv = new JMenuItem("CSV…"); csv.setOpaque(true); nested.add(csv);
                for (var listener : menu.getPopupMenuListeners())
                    listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                JPopupMenu child = nested.getPopupMenu();
                for (var listener : child.getPopupMenuListeners())
                    listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(child));
                // Swing fills an opaque hovered item with the selection color alone; a translucent one must land on the popup's fill.
                assertFalse(count.isOpaque()); assertFalse(nested.isOpaque()); assertFalse(csv.isOpaque());
                assertTrue(menu.isOpaque()); assertTrue(child.isOpaque());
            } finally {
                closePopup(nested.getPopupMenu());
                closePopup(menu);
            }
        });
    }

    private static void closePopup(JPopupMenu popup) {
        for (var listener : popup.getPopupMenuListeners()) listener.popupMenuWillBecomeInvisible(new javax.swing.event.PopupMenuEvent(popup));
    }

    @Test void emptyAndCommentOnlySqlCannotRunButExecutableCommentsAndStatementsCan() {
        for (String sql : new String[]{"", " \n\t", "; ;", "-- Write your query\n", "/* explanation */ ; # comment\r\n", "/* unfinished"})
            assertFalse(SqlQueryConsolePanel.hasExecutableSql(sql), sql);
        for (String sql : new String[]{"SELECT 1", "-- comment\nSELECT 1", "/* note */ SELECT '/*'", "/*! SELECT 1 */"})
            assertTrue(SqlQueryConsolePanel.hasExecutableSql(sql), sql);
    }

    @Test void applyIsAvailableForClausesAndForClearingAnAppliedFilterOrSort() {
        assertFalse(TableDataEditorPanel.canApplyFilter(" \t", "", "", ""));
        assertTrue(TableDataEditorPanel.canApplyFilter("id = 1", "", "", ""));
        assertTrue(TableDataEditorPanel.canApplyFilter("", "id DESC", "", ""));
        assertTrue(TableDataEditorPanel.canApplyFilter("", "", "id = 1", ""));
        assertTrue(TableDataEditorPanel.canApplyFilter("", "", "", "id DESC"));
    }

    @Test void exportSubmenusUseTheNativePopupSurfaceAndKeepSwingMenuNavigation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenu submenu = DatabaseUi.submenu("Current page");
            JMenuItem sql = new JMenuItem("SQL…");
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            sql.addActionListener(event -> calls.incrementAndGet());
            submenu.add(sql);
            for (int pass = 0; pass < 2; pass++) {
                if (pass > 0) SwingUtilities.updateComponentTreeUI(submenu);
                JPopupMenu popup = submenu.getPopupMenu();
                assertTrue(popup.isOpaque());
                assertFalse(popup.isLightWeightPopupEnabled());
                assertSame(submenu, popup.getInvoker());
                assertSame(sql, submenu.getItem(0));
                assertSame(popup, submenu.getSubElements()[0]);
            }
            sql.doClick(0);
            assertEquals(1, calls.get());
        });
    }

    @Test void overflowActionMirrorsStateAndInvokesTheVisibleButtonWithoutRetainingClosedViews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton source = DatabaseUi.action("Commit", com.intellij.icons.AllIcons.Actions.Checked, "Commit changes");
            source.setEnabled(false);
            java.util.List<Object> invokers = new java.util.ArrayList<>();
            source.addActionListener(event -> invokers.add(event.getSource()));
            int listeners = source.getPropertyChangeListeners().length;
            JButton popup = DatabaseUi.mirrorAction(source);
            popup.addNotify();
            assertFalse(popup.isEnabled());
            source.setEnabled(true);
            DatabaseUi.setActionEmphasis(source, DatabaseUi.POSITIVE_ACTION_COLOR);
            assertTrue(popup.isEnabled());
            assertEquals("", popup.getText(), "The overflow view must stay icon-only after state updates");
            assertEquals("Commit", source.getText());
            assertEquals("Commit changes", popup.getToolTipText());
            assertEquals(DatabaseUi.POSITIVE_ACTION_COLOR, popup.getClientProperty("lattice.action.emphasis"));
            popup.doClick(0);
            assertEquals(java.util.List.of(popup), invokers, "Menus must receive their actual popup invoker");
            popup.removeNotify();
            assertEquals(listeners, source.getPropertyChangeListeners().length, "Closing overflow must detach its state listener");
        });
    }

}
