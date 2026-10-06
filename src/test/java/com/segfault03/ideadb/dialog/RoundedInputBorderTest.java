package com.segfault03.ideadb.dialog;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class RoundedInputBorderTest {
    @Test
    void roundedOutlinePreservesTheParentBackgroundAtCorners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel parent = new JPanel();
            parent.setBackground(new Color(0x123456));
            JTextField field = ConnectionFormPanel.width(new JTextField(), 220);
            field.setBackground(Color.WHITE);
            parent.add(field);
            field.setSize(field.getPreferredSize());
            BufferedImage image = new BufferedImage(field.getWidth(), field.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            field.paint(graphics);
            graphics.dispose();
            assertEquals(parent.getBackground().getRGB(), image.getRGB(0, 0));
            assertEquals(parent.getBackground().getRGB(), image.getRGB(field.getWidth()-1, 0));
            assertEquals(Color.WHITE.getRGB(), image.getRGB(field.getWidth()/2, field.getHeight()/2));
            assertNotEquals(parent.getBackground().getRGB(), image.getRGB(field.getWidth()/2, 1),
                    "The top edge must retain a visible outline");
            assertNotEquals(Color.WHITE.getRGB(), image.getRGB(field.getWidth()/2, 1));
        });
    }
}
