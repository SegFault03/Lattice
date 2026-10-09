package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import com.intellij.openapi.actionSystem.Presentation;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUiTest {
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
