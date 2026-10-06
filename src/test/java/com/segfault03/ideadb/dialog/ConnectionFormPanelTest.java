package com.segfault03.ideadb.dialog;

import org.junit.jupiter.api.Test;

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
            JComboBox<String> mode = ConnectionFormPanel.width(new JComboBox<>(new String[]{"Remote Server (hsql://)"}), 280);
            hsql.addRow(0, "Mode:", mode, false);
            CardLayout cards = new CardLayout();
            JPanel subforms = new JPanel(cards);
            JTextField[] modeInputs = new JTextField[3];
            for (int i = 0; i < modeInputs.length; i++) {
                ConnectionFormPanel subform = new ConnectionFormPanel();
                modeInputs[i] = ConnectionFormPanel.width(new JTextField(), 280);
                subform.addRow(0, new String[]{"Host:", "File / Path:", "Database Name:"}[i], modeInputs[i], false);
                if (i == 0) subform.addRow(1, "Database:", new JTextField(), true);
                subforms.add(subform, String.valueOf(i));
            }
            hsql.addFullWidthRow(1, subforms);
            JTextField user = ConnectionFormPanel.width(new JTextField(), 220);
            JPasswordField password = ConnectionFormPanel.width(new JPasswordField(), 220);
            hsql.addRow(2, "User:", user, false);
            hsql.addRow(3, "Password:", password, false);
            root.add(hsql, BorderLayout.CENTER);

            for (int width : new int[]{600, 800}) {
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
                    assertTrue(password.getY() > user.getY() + user.getHeight(), "Credential rows must not overlap");
                    assertEquals(0, hsql.getInsets().left, "Settings must not add an outer frame or padding");
                }
            }
        });
    }

    @Test
    void hostUsesAvailableWidthWhilePortRemainsReadable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField host = new JTextField("localhost");
            JTextField port = new JTextField("9001");
            JPanel row = ConnectionFormPanel.hostAndPort(host, port);
            for (int width : new int[]{350, 550}) {
                row.setSize(width, row.getPreferredSize().height);
                layout(row);
                Point portPosition = SwingUtilities.convertPoint(port, 0, 0, row);
                assertEquals(port.getPreferredSize().width, port.getWidth());
                assertTrue(host.getWidth() >= 200, "Host should remain usable");
                assertTrue(host.getX() + host.getWidth() < portPosition.x, "Host must not overlap the port group");
                assertTrue(portPosition.x + port.getWidth() <= width, "Port must remain inside the form");
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
