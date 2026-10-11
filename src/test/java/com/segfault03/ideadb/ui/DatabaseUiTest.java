package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import com.intellij.openapi.actionSystem.Presentation;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUiTest {
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

    @Test void mouseFocusIsQuietAndKeyboardTraversalRestoresTheCue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] controls = {DatabaseUi.action("Options", null, "Options"), DatabaseInputs.comboBox(new String[]{"Off"})};
            for (JComponent control : controls) {
                java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(control,
                        java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 2, 2, 1, false);
                for (var listener : control.getMouseListeners()) listener.mousePressed(click);
                assertFalse(DatabaseUi.isFocusVisible(control));
                java.awt.event.FocusEvent traversal = new java.awt.event.FocusEvent(control,
                        java.awt.event.FocusEvent.FOCUS_GAINED, false, null, java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
                for (var listener : control.getFocusListeners()) listener.focusGained(traversal);
                assertTrue(DatabaseUi.isFocusVisible(control));
            }
        });
    }

    @Test void disabledGlyphUsesTheLabelColorAndPreservesAlphaIncludingInToolbarPresentation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object previous = UIManager.get("Button.disabledText");
            try {
                UIManager.put("Button.disabledText", new Color(0xA0A8B0));
                Icon icon = new Icon() {
                    public int getIconWidth() { return 2; }
                    public int getIconHeight() { return 1; }
                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        g.setColor(Color.BLACK); g.fillRect(x, y, 1, 1);
                        g.setColor(new Color(0, 0, 0, 128)); g.fillRect(x + 1, y, 1, 1);
                    }
                };
                JButton button = DatabaseUi.action("Commit", icon, "Commit");
                DatabaseUi.setActionEmphasis(button, DatabaseUi.POSITIVE_ACTION_COLOR);
                button.setEnabled(false);
                var pixels = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = pixels.createGraphics();
                try { button.getDisabledIcon().paintIcon(button, graphics, 0, 0); }
                finally { graphics.dispose(); }
                assertEquals(0xFFA0A8B0, pixels.getRGB(0, 0));
                int translucent = pixels.getRGB(1, 0);
                assertEquals(128, translucent >>> 24, "Antialiasing alpha must be preserved");
                Color painted = new Color(translucent, true);
                assertTrue(Math.abs(painted.getRed() - 160) <= 1 && Math.abs(painted.getGreen() - 168) <= 1
                        && Math.abs(painted.getBlue() - 176) <= 1, "Allow premultiplied-alpha rounding");
                Presentation presentation = new Presentation();
                new DatabaseActionToolbar.ControlAction(button, null).updatePresentation(presentation);
                assertSame(button.getDisabledIcon(), presentation.getDisabledIcon());
                assertFalse(presentation.isEnabled());
                UIManager.put("Button.disabledText", new Color(0x606870));
                assertNotSame(presentation.getDisabledIcon(), button.getDisabledIcon(), "Theme changes must refresh the glyph");
            } finally { UIManager.put("Button.disabledText", previous); }
        });
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

    @Test void emphasizedGlyphIsWhiteRatherThanGrayAndPreservesTransparency() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon gray = new Icon() {
                public int getIconWidth() { return 2; }
                public int getIconHeight() { return 1; }
                public void paintIcon(Component component, Graphics graphics, int x, int y) {
                    graphics.setColor(new Color(100, 100, 100));
                    graphics.fillRect(x, y, 1, 1);
                    graphics.setColor(new Color(50, 50, 50, 128));
                    graphics.fillRect(x + 1, y, 1, 1);
                }
            };
            JButton button = DatabaseUi.action("Commit", gray, "Commit");
            DatabaseUi.setActionEmphasis(button, DatabaseUi.POSITIVE_ACTION_COLOR);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 1,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { button.getIcon().paintIcon(button, graphics, 0, 0); }
            finally { graphics.dispose(); }
            assertEquals(0xFFFFFFFF, image.getRGB(0, 0), "Opaque gray must become opaque white");
            assertEquals(0x80FFFFFF, image.getRGB(1, 0), "Antialiasing alpha must be preserved");
            DatabaseUi.setActionEmphasis(button, null);
            assertSame(gray, button.getIcon(), "Inactive actions must restore the native glyph");
        });
    }

    @Test void nativeToolbarPresentationTracksTheEmphasizedIconAndWhiteLabel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton source = DatabaseUi.action("Commit", com.intellij.icons.AllIcons.Actions.Checked, "Commit changes");
            source.setEnabled(true);
            DatabaseActionToolbar.ControlAction action = new DatabaseActionToolbar.ControlAction(source, null);
            Presentation initial = new Presentation();
            action.updatePresentation(initial);

            DatabaseUi.setActionEmphasis(source, DatabaseUi.POSITIVE_ACTION_COLOR);
            assertEquals(Color.WHITE, source.getForeground(), "Active action text must be white");

            Presentation presentation = new Presentation();
            action.updatePresentation(presentation);
            assertNotSame(initial.getIcon(), presentation.getIcon(),
                    "The active toolbar icon must be recolored instead of retaining the disabled-style icon");
            assertSame(source.getIcon(), presentation.getIcon(),
                    "The native toolbar must use the current emphasized icon instead of its initial gray icon");

            JButton overflow = DatabaseUi.mirrorAction(source);
            overflow.addNotify();
            assertEquals(Color.WHITE, overflow.getForeground(), "Active overflow label must be white");
            assertEquals(DatabaseUi.POSITIVE_ACTION_COLOR, overflow.getClientProperty("lattice.action.emphasis"));
            overflow.removeNotify();
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

    @Test void nativeToolbarActionsStayCompactAndLabelBaselinesAlignWithFields() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon icon = new Icon() {
                public int getIconWidth() { return 16; }
                public int getIconHeight() { return 16; }
                public void paintIcon(Component c, Graphics g, int x, int y) {}
            };
            JButton iconOnly = DatabaseUi.action("", icon, "Refresh");
            JButton run = DatabaseUi.action("Run", icon, "Run query");
            for (JButton button : new JButton[]{iconOnly, run}) {
                button.setUI(new com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI());
                assertFalse(button.isContentAreaFilled());
                assertFalse(button.isBorderPainted());
            }
            assertEquals(28, iconOnly.getPreferredSize().width);
            assertTrue(run.getPreferredSize().width < 80);
            JLabel label = new JLabel("Database");
            JTextField field = DatabaseInputs.textField("PUBLIC");
            field.setPreferredSize(new Dimension(150, field.getPreferredSize().height));
            JPanel group = DatabaseUi.group(label, field);
            group.setSize(group.getPreferredSize());
            group.doLayout();
            assertEquals(label.getY() + label.getBaseline(label.getWidth(), label.getHeight()),
                    field.getY() + field.getBaseline(field.getWidth(), field.getHeight()));
        });
    }
}
