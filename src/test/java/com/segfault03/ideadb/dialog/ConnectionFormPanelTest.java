package com.segfault03.ideadb.dialog;

import org.junit.jupiter.api.Test;
import com.segfault03.ideadb.ui.VisibleCardPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionFormPanelTest {
    @Test
    void nestedHsqlModesKeepLabelsAndInputsAlignedWhenResized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(new BorderLayout());
            ConnectionFormPanel header = new ConnectionFormPanel();
            JTextField name = new JTextField();
            header.addRow(0, "Name:", name, true);
            root.add(header, BorderLayout.NORTH);

            ConnectionFormPanel hsql = new ConnectionFormPanel();
            JComboBox<String> mode = ConnectionFormPanel.width(new JComboBox<>(new String[]{"Remote Server (hsql://)"}), 220);
            hsql.addRow(0, "Mode:", mode, false);
            CardLayout cards = new CardLayout();
            JPanel subforms = new VisibleCardPanel(cards);
            JTextField[] modeInputs = new JTextField[3];
            for (int i = 0; i < modeInputs.length; i++) {
                ConnectionFormPanel subform = new ConnectionFormPanel();
                modeInputs[i] = ConnectionFormPanel.width(new JTextField(), 220);
                subform.addRow(0, new String[]{"Host:", "File / Path:", "Database Name:"}[i], modeInputs[i], false);
                if (i == 0) {
                    subform.addRow(1, "Port:", ConnectionFormPanel.width(new JTextField(), 76), false);
                    subform.addRow(2, "Database:", ConnectionFormPanel.width(new JTextField(), 220), false);
                }
                subforms.add(subform, String.valueOf(i));
            }
            hsql.addFullWidthRow(1, subforms);
            JTextField user = ConnectionFormPanel.width(new JTextField(), 220);
            JPasswordField password = ConnectionFormPanel.width(new JPasswordField(), 220);
            hsql.addRow(2, "User:", user, false);
            hsql.addRow(3, "Password:", password, false);
            root.add(hsql, BorderLayout.CENTER);

            for (int width : new int[]{520, 800}) {
                root.setSize(width, 500);
                for (int selected = 0; selected < modeInputs.length; selected++) {
                    cards.show(subforms, String.valueOf(selected));
                    layout(root);
                    int inputX = xInRoot(name, root);
                    assertEquals(inputX, xInRoot(mode, root), "Mode must align with the header");
                    assertEquals(inputX, xInRoot(modeInputs[selected], root), "Each HSQL mode must share the label column");
                    assertEquals(inputX, xInRoot(user, root));
                    assertEquals(inputX, xInRoot(password, root));
                    assertEquals(user.getPreferredSize().width, user.getWidth(), "Credentials must not shrink or stretch");
                    assertEquals(mode.getPreferredSize().width, mode.getWidth(), "The selected mode must remain readable");
                    if (selected > 0) {
                        int inputBottom = SwingUtilities.convertPoint(modeInputs[selected], 0, modeInputs[selected].getHeight(), root).y;
                        int userTop = SwingUtilities.convertPoint(user, 0, 0, root).y;
                        assertTrue(userTop - inputBottom <= JBUI.scale(20), "Memory/file mode must not reserve empty server rows before credentials");
                    }
                    assertTrue(password.getY() > user.getY() + user.getHeight(), "Credential rows must not overlap");
                    assertEquals(0, hsql.getInsets().left, "Settings must not add an outer frame or padding");
                }
            }
        });
    }

    @Test
    void fieldsKeepTheirWidthsAndPortStaysBelowHostWhenResized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConnectionFormPanel form = new ConnectionFormPanel();
            String[] labels = {"Name:", "Host:", "Port:", "Database:", "User:", "Password:", "JDBC URL:"};
            int[] widths = {360, 220, 76, 220, 220, 220, 360};
            JTextField[] fields = new JTextField[labels.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = ConnectionFormPanel.width(new JTextField(), widths[i]);
                form.addRow(i, labels[i], fields[i], false);
            }
            for (int width : new int[]{520, 800}) {
                form.setSize(width, 500);
                layout(form);
                for (int i = 0; i < fields.length; i++) {
                    assertEquals(fields[0].getX(), fields[i].getX());
                    assertEquals(fields[i].getPreferredSize().width, fields[i].getWidth());
                    assertTrue(fields[i].getX() + fields[i].getWidth() <= width);
                    if (i > 0) assertTrue(fields[i].getY() > fields[i-1].getY() + fields[i-1].getHeight());
                }
                assertTrue(fields[0].getWidth() > fields[1].getWidth());
                assertTrue(fields[2].getWidth() < fields[1].getWidth());
                for (Component child : form.getComponents()) {
                    if (child instanceof JLabel) assertEquals(0, child.getX());
                }
            }
        });
    }

    private static int xInRoot(JComponent input, JPanel root) {
        return SwingUtilities.convertPoint(input, 0, 0, root).x;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
