package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUiTest {
    @Test void cachedPopupAndSubmenuRefreshTheirPaletteOnEveryOpen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] keys = {"PopupMenu.background", "MenuItem.background", "MenuItem.foreground"};
            Object[] previous = java.util.Arrays.stream(keys).map(UIManager::get).toArray();
            try {
                JPopupMenu menu = DatabaseUi.popupMenu();
                JMenuItem count = new JMenuItem("Count rows"); menu.add(count);
                JMenu nested = DatabaseUi.submenu("More"); menu.add(nested);
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
                    assertFalse(menu.isOpaque()); assertFalse(child.isOpaque());
                    assertFalse(menu.isLightWeightPopupEnabled()); assertFalse(child.isLightWeightPopupEnabled());
                    assertEquals(listeners, menu.getPopupMenuListeners().length, "Opening must not accumulate listeners");
                }
            } finally { for (int index = 0; index < keys.length; index++) UIManager.put(keys[index], previous[index]); }
        });
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
                assertFalse(popup.isOpaque());
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
